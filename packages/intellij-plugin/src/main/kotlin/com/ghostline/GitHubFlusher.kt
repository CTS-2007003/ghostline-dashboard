package com.ghostline

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.net.ssl.CertificateManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
  private val log = Logger.getInstance(GitHubFlusher::class.java)

  private val client = run {
    val cm = CertificateManager.getInstance()
    OkHttpClient.Builder()
      .sslSocketFactory(cm.sslContext.socketFactory, cm.trustManager)
      .build()
  }
  private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
  private val JSON = "application/json".toMediaType()

  @Synchronized
  fun flush() {
    log.info("Ghostline: flush() called")
    val session = SessionStore.getInstance()
    val settings = GhostlineSettings.getInstance()

    val token = AuthManager.getInstance().getToken()
    if (token == null) { log.warn("Ghostline: no token set — skipping flush"); return }

    val username = settings.githubUsername
    if (username.isBlank()) { log.warn("Ghostline: username not set — skipping flush"); return }

    val parts = settings.githubRepo.split("/")
    if (parts.size != 2) { log.warn("Ghostline: invalid repo '${settings.githubRepo}' — skipping flush"); return }
    val (owner, repo) = parts

    val totalSnap = session.totalLines

    // Read AI lines BEFORE the early-exit check — AI may have written lines
    // even if the developer typed nothing this interval
    val aiSnap = ProjectManager.getInstance().openProjects
      .mapNotNull { it.basePath }
      .sumOf { WorkspaceInstructor.readAndResetSessionFile(it) }

    log.info("Ghostline: totalSnap=$totalSnap aiSnap=$aiSnap")

    if (totalSnap == 0 && aiSnap == 0) { log.info("Ghostline: nothing to flush"); return }

    // Effective total: if AI wrote lines to files not open in the editor,
    // DocumentTracker won't have counted them — use AI lines as the floor
    val effectiveTotal = maxOf(totalSnap, aiSnap)

    // Reset before network calls — prevents double-count if timer + close overlap
    session.reset()

    try {
      val path = "data/$username.json"
      val (existing, sha) = readFile(token, owner, repo, path)

      val displayName = settings.displayName.ifBlank { username }
      val team = settings.team
      val data = existing ?: DevData(username = username, display_name = displayName, team = team)
      data.display_name = displayName
      if (team.isNotBlank()) data.team = team
      if (!data.ides.contains("intellij")) data.ides.add("intellij")
      data.total_lines_written += effectiveTotal
      data.total_ai_lines += aiSnap
      data.last_updated = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

      val today = LocalDate.now().toString()
      val entry = data.history.find { it.date == today }
      if (entry != null) {
        val idx = data.history.indexOf(entry)
        data.history[idx] = entry.copy(total = entry.total + effectiveTotal, ai = entry.ai + aiSnap)
      } else {
        data.history.add(HistoryEntry(today, effectiveTotal, aiSnap))
      }

      if (data.history.size > 90) data.history = data.history.takeLast(90).toMutableList()

      writeFile(token, owner, repo, path, data, sha)
      ensureInIndex(token, owner, repo, data.username)
      log.info("Ghostline: flush complete — wrote $effectiveTotal total, $aiSnap AI lines")
    } catch (t: Throwable) {
      log.error("Ghostline: flush failed", t)
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
