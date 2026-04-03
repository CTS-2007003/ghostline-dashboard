package com.ghostline

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.service
import com.intellij.openapi.application.ApplicationManager
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
  val ide: String = "intellij",
  var total_lines_written: Int = 0,
  var total_ai_lines: Int = 0,
  var history: MutableList<HistoryEntry> = mutableListOf(),
  var last_updated: String = ""
)

object GitHubFlusher {
  private val client = OkHttpClient()
  private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
  private val JSON = "application/json".toMediaType()

  fun flush() {
    val session = SessionStore.getInstance()
    if (session.totalLines == 0 && session.aiLines == 0) return

    val settings = GhostlineSettings.getInstance()
    val token = AuthManager.getInstance().getToken() ?: return
    val username = settings.githubUsername.ifBlank { return }
    val (owner, repo) = settings.githubRepo.split("/").takeIf { it.size == 2 } ?: return

    val totalSnap = session.totalLines
    val aiSnap = session.aiLines

    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val path = "data/$username.json"
        val (existing, sha) = readFile(token, owner, repo, path)

        val data = existing ?: DevData(username = username)
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

        // Keep last 90 days
        if (data.history.size > 90) data.history = data.history.takeLast(90).toMutableList()

        writeFile(token, owner, repo, path, data, sha)
        ensureInIndex(token, owner, repo, data.username)
        session.reset()
      } catch (e: Exception) {
        // Silent fail — will retry on next flush
      }
    }
  }

  private fun ensureInIndex(token: String, owner: String, repo: String, username: String) {
    val req = Request.Builder()
      .url("https://api.github.com/repos/$owner/$repo/contents/data/index.json")
      .header("Authorization", "token $token")
      .header("Accept", "application/vnd.github.v3+json")
      .get().build()

    val res = client.newCall(req).execute()
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

    client.newCall(putReq).execute()
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
