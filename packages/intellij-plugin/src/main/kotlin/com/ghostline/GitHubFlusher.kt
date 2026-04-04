package com.ghostline

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.net.ssl.CertificateManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.Instant
import java.util.Base64

data class HistoryEntry(val date: String, val total: Int, val ai: Int)

data class DevData(
  val username: String,
  var display_name: String = "",
  var team: String = "",
  var ides: MutableList<String> = mutableListOf(),
  var total_lines_written: Int = 0,
  var total_ai_lines: Int = 0,
  var history: MutableList<HistoryEntry> = mutableListOf(),
  var last_updated: String = ""
)

object GitHubFlusher {
  private val client = run {
    val cm = CertificateManager.getInstance()
    OkHttpClient.Builder()
      .sslSocketFactory(cm.sslContext.socketFactory, cm.trustManager)
      .build()
  }
  private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
  private val JSON = "application/json".toMediaType()

  @Synchronized
  fun flush(synchronous: Boolean = false) {
    val session = SessionStore.getInstance()
    if (session.totalLines == 0) return

    val settings = GhostlineSettings.getInstance()
    val token = AuthManager.getInstance().getToken() ?: return
    val username = settings.githubUsername.ifBlank { return }
    val (owner, repo) = settings.githubRepo.split("/").takeIf { it.size == 2 } ?: return

    val totalSnap = session.totalLines

    // Collect AI lines from all open projects' session.json files
    val rawAiLines = ProjectManager.getInstance().openProjects
      .mapNotNull { it.basePath }
      .sumOf { WorkspaceInstructor.readAndResetSessionFile(it) }
    val aiSnap = minOf(rawAiLines, totalSnap)

    // Reset before dispatching — prevents a second concurrent flush from
    // double-counting the same lines if the timer and appWillBeClosed overlap
    session.reset()

    val work = Runnable {
      try {
        val path = "data/$username.json"
        val (existing, sha) = readFile(token, owner, repo, path)

        val displayName = settings.displayName.ifBlank { username }
        val team = settings.team
        val data = existing ?: DevData(username = username, display_name = displayName, team = team)
        data.display_name = displayName
        if (team.isNotBlank()) data.team = team
        if (!data.ides.contains("intellij")) data.ides.add("intellij")
        data.total_lines_written += totalSnap
        data.total_ai_lines += aiSnap
        data.last_updated = Instant.now().toString()

        val today = LocalDate.now().toString()
        val entry = data.history.find { it.date == today }
        if (entry != null) {
          val idx = data.history.indexOf(entry)
          data.history[idx] = entry.copy(total = entry.total + totalSnap, ai = entry.ai + aiSnap)
        } else {
          data.history.add(HistoryEntry(today, totalSnap, aiSnap))
        }

        if (data.history.size > 90) data.history = data.history.takeLast(90).toMutableList()

        writeFile(token, owner, repo, path, data, sha)
        ensureInIndex(token, owner, repo, data.username)
      } catch (e: Exception) {
        // Silent fail — will retry on next flush
      }
    }

    if (synchronous) {
      work.run()
    } else {
      ApplicationManager.getApplication().executeOnPooledThread(work)
    }
  }

  private fun ensureInIndex(token: String, owner: String, repo: String, username: String, retries: Int = 1) {
    try {
      val getReq = Request.Builder()
        .url("https://api.github.com/repos/$owner/$repo/contents/data/index.json")
        .header("Authorization", "token $token")
        .header("Accept", "application/vnd.github.v3+json")
        .get().build()

      val res = client.newCall(getReq).execute()
      val listType = object : TypeToken<MutableList<String>>() {}.type

      val (index, sha) = if (res.isSuccessful) {
        val body = gson.fromJson(res.body?.string(), Map::class.java)
        val content = String(Base64.getMimeDecoder().decode(body["content"] as String))
        Pair(gson.fromJson<MutableList<String>>(content, listType), body["sha"] as String?)
      } else {
        Pair(mutableListOf(), null)
      }

      if (index.contains(username)) return  // already registered, skip write

      index.add(username)
      index.sort()

      val encoded = Base64.getEncoder().encodeToString(gson.toJson(index).toByteArray())
      val bodyMap = mutableMapOf(
        "message" to "ghostline: register $username [skip ci]",
        "content" to encoded
      )
      if (sha != null) bodyMap["sha"] = sha

      val putReq = Request.Builder()
        .url("https://api.github.com/repos/$owner/$repo/contents/data/index.json")
        .header("Authorization", "token $token")
        .header("Accept", "application/vnd.github.v3+json")
        .put(gson.toJson(bodyMap).toRequestBody(JSON))
        .build()

      val putRes = client.newCall(putReq).execute()
      // 422 = SHA conflict (another dev registered simultaneously) — retry once with fresh SHA
      if (!putRes.isSuccessful && putRes.code == 422 && retries > 0) {
        ensureInIndex(token, owner, repo, username, retries - 1)
      }
    } catch (_: Exception) {}
  }

  private fun readFile(token: String, owner: String, repo: String, path: String): Pair<DevData?, String?> {
    val req = Request.Builder()
      .url("https://api.github.com/repos/$owner/$repo/contents/$path")
      .header("Authorization", "token $token")
      .header("Accept", "application/vnd.github.v3+json")
      .get().build()

    val res = client.newCall(req).execute()
    if (!res.isSuccessful) return Pair(null, null)

    val body = gson.fromJson(res.body?.string(), Map::class.java)
    val content = String(Base64.getMimeDecoder().decode(body["content"] as String))
    val sha = body["sha"] as String

    val type = object : TypeToken<DevData>() {}.type
    return Pair(gson.fromJson(content, type), sha)
  }

  private fun writeFile(token: String, owner: String, repo: String, path: String, data: DevData, sha: String?) {
    val content = Base64.getEncoder().encodeToString(gson.toJson(data).toByteArray())
    val bodyMap = mutableMapOf(
      "message" to "ghostline: sync ${data.username} [skip ci]",
      "content" to content
    )
    if (sha != null) bodyMap["sha"] = sha

    val req = Request.Builder()
      .url("https://api.github.com/repos/$owner/$repo/contents/$path")
      .header("Authorization", "token $token")
      .header("Accept", "application/vnd.github.v3+json")
      .put(gson.toJson(bodyMap).toRequestBody(JSON))
      .build()

    client.newCall(req).execute()
  }
}
