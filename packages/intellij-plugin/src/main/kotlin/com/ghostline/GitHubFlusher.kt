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
import java.util.concurrent.ConcurrentHashMap

enum class FlushResult { SYNCED, NOTHING, NO_CONFIG }

data class HistoryEntry(
  val date: String,
  val total: Int,
  val ai:    Int,
  val dev:   Int = 0,
  val test:  Int = 0
)

data class DevData(
  val username: String,
  var display_name:       String                   = "",
  var team:               String                   = "",
  var total_lines_written: Int                     = 0,
  var total_ai_lines:     Int                      = 0,
  var dev_lines_written:  Int                      = 0,
  var test_lines_written: Int                      = 0,
  var history:            MutableList<HistoryEntry> = mutableListOf(),
  var last_updated:       String                   = ""
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
  private val fmt  = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

  // Tracks what's already been written to local stats — avoids double-counting
  // on repeated 15-min writes and on flush(). Owned here so both the timer
  // (flushLocal) and the GitHub flush path share the same snapshot.
  private val localWrittenSnapshot = ConcurrentHashMap<String, ExtLines>()

  /**
   * Write the delta (lines typed since last local write) to ~/.ghostline/stats.json.
   * Called every 15 minutes by AppLifecycleHandler and at the start of flush().
   */
  @Synchronized
  fun flushLocal() {
    val session = SessionStore.getInstance()
    val current = session.snapshot()
    val delta   = mutableMapOf<String, ExtLines>()

    for ((ext, lines) in current) {
      val written = localWrittenSnapshot[ext] ?: ExtLines(0, 0)
      val dDev  = lines.dev  - written.dev
      val dTest = lines.test - written.test
      if (dDev > 0 || dTest > 0) {
        delta[ext] = ExtLines(maxOf(0, dDev), maxOf(0, dTest))
        localWrittenSnapshot[ext] = ExtLines(lines.dev, lines.test)
      }
    }

    if (delta.isNotEmpty()) LocalStatsWriter.writeDelta(delta)
  }

  /**
   * Write local stats + push to GitHub.
   * - Always writes local stats and resets session first.
   * - Merges any pending data from a previous failed sync.
   * - Writes pending.json before the GitHub call; clears it on success.
   * - Returns SYNCED | NOTHING | NO_CONFIG. Throws on network error.
   */
  @Synchronized
  fun flush(): FlushResult {
    log.info("Ghostline: flush() called")
    val session  = SessionStore.getInstance()
    val settings = GhostlineSettings.getInstance()

    val totalSnap = session.totalLines()
    val devSnap   = session.devLines()
    val testSnap  = session.testLines()

    // Write only the delta not yet persisted locally (avoids double-counting with the 15-min timer)
    flushLocal()
    session.reset()
    // Clear snapshot — session is now 0, next flushLocal() starts fresh
    localWrittenSnapshot.clear()

    val aiSnap = ProjectManager.getInstance().openProjects
      .mapNotNull { it.basePath }
      .sumOf { WorkspaceInstructor.readAndResetSessionFile(it) }

    log.info("Ghostline: totalSnap=$totalSnap devSnap=$devSnap testSnap=$testSnap aiSnap=$aiSnap")

    val token = AuthManager.getInstance().getToken()
    if (token == null) { log.warn("Ghostline: no token — skipping GitHub push"); return FlushResult.NO_CONFIG }

    val username = settings.githubUsername
    if (username.isBlank()) { log.warn("Ghostline: username not set"); return FlushResult.NO_CONFIG }

    val parts = settings.githubRepo.split("/")
    if (parts.size != 2) { log.warn("Ghostline: invalid repo '${settings.githubRepo}'"); return FlushResult.NO_CONFIG }
    val (owner, repo) = parts

    val effectiveTotal  = maxOf(totalSnap, aiSnap)
    val existingPending = LocalStatsWriter.readPending()

    if (effectiveTotal == 0 && existingPending == null) {
      log.info("Ghostline: nothing to flush")
      return FlushResult.NOTHING
    }

    // Merge current session with any previously failed pending sync
    val combined = PendingSync(
      date  = LocalDate.now().toString(),
      total = effectiveTotal        + (existingPending?.total ?: 0),
      ai    = aiSnap                + (existingPending?.ai    ?: 0),
      dev   = devSnap               + (existingPending?.dev   ?: 0),
      test  = testSnap              + (existingPending?.test  ?: 0)
    )

    // Write pending BEFORE the GitHub call — if network fails, retried on next open
    LocalStatsWriter.writePending(combined)

    try {
      val path = "data/$username.json"
      val (existing, sha) = readFile(token, owner, repo, path)

      val displayName = settings.displayName.ifBlank { username }
      val team        = settings.team
      val data = existing ?: DevData(username = username, display_name = displayName, team = team)
      data.display_name      = displayName
      if (team.isNotBlank()) data.team = team
      data.total_lines_written += combined.total
      data.total_ai_lines      += combined.ai
      data.dev_lines_written   += combined.dev
      data.test_lines_written  += combined.test
      data.last_updated         = ZonedDateTime.now().format(fmt)

      if (data.history == null) data.history = mutableListOf()
      val today = LocalDate.now().toString()
      val entry = data.history.find { it.date == today }
      if (entry != null) {
        val idx = data.history.indexOf(entry)
        data.history[idx] = entry.copy(
          total = entry.total + combined.total,
          ai    = entry.ai    + combined.ai,
          dev   = entry.dev   + combined.dev,
          test  = entry.test  + combined.test
        )
      } else {
        data.history.add(HistoryEntry(today, combined.total, combined.ai, combined.dev, combined.test))
      }
      if (data.history.size > 90) data.history = data.history.takeLast(90).toMutableList()

      writeFile(token, owner, repo, path, data, sha)
      ensureInIndex(token, owner, repo, data.username)

      // Only clear pending after confirmed success
      LocalStatsWriter.clearPending()
      log.info("Ghostline: flush complete — ${combined.total} total (${combined.dev} dev, ${combined.test} test, ${combined.ai} AI)")
      return FlushResult.SYNCED
    } catch (t: Throwable) {
      log.error("Ghostline: GitHub push failed — pending.json preserved for retry", t)
      throw t  // caller decides whether to show a notification
    }
  }

  /**
   * Retry a previously failed sync from pending.json.
   * Called silently on IDE open. No-op if no pending data.
   */
  @Synchronized
  fun pushPending() {
    // Persist any lines typed in the startup window before retrying the pending push,
    // so a crash after this point doesn't lose them from local stats.
    flushLocal()

    val pending = LocalStatsWriter.readPending() ?: return
    val settings = GhostlineSettings.getInstance()

    val token = AuthManager.getInstance().getToken() ?: run {
      log.info("Ghostline: pushPending skipped — no token")
      return
    }
    if (settings.githubUsername.isBlank() || settings.githubRepo.isBlank()) return

    val parts = settings.githubRepo.split("/")
    if (parts.size != 2) return
    val (owner, repo) = parts

    try {
      val path = "data/${settings.githubUsername}.json"
      val (existing, sha) = readFile(token, owner, repo, path)

      val displayName = settings.displayName.ifBlank { settings.githubUsername }
      val data = existing ?: DevData(username = settings.githubUsername, display_name = displayName, team = settings.team)
      data.total_lines_written += pending.total
      data.total_ai_lines      += pending.ai
      data.dev_lines_written   += pending.dev
      data.test_lines_written  += pending.test
      data.last_updated         = ZonedDateTime.now().format(fmt)

      if (data.history == null) data.history = mutableListOf()
      val today = LocalDate.now().toString()
      val entry = data.history.find { it.date == today }
      if (entry != null) {
        val idx = data.history.indexOf(entry)
        data.history[idx] = entry.copy(
          total = entry.total + pending.total,
          ai    = entry.ai    + pending.ai,
          dev   = entry.dev   + pending.dev,
          test  = entry.test  + pending.test
        )
      } else {
        data.history.add(HistoryEntry(today, pending.total, pending.ai, pending.dev, pending.test))
      }
      if (data.history.size > 90) data.history = data.history.takeLast(90).toMutableList()

      writeFile(token, owner, repo, path, data, sha)
      ensureInIndex(token, owner, repo, data.username)
      LocalStatsWriter.clearPending()
      log.info("Ghostline: pending sync pushed successfully")
    } catch (t: Throwable) {
      log.warn("Ghostline: pending retry failed — will try again next open: ${t.message}")
      // Don't rethrow — silent failure, pending.json preserved
    }
  }

  private fun ensureInIndex(token: String, owner: String, repo: String, username: String, retries: Int = 1) {
    val getReq = Request.Builder()
      .url("https://api.github.com/repos/$owner/$repo/contents/data/index.json")
      .header("Authorization", "token $token")
      .header("Accept", "application/vnd.github.v3+json")
      .get().build()

    val listType = object : TypeToken<MutableList<String>>() {}.type
    val (index, sha) = client.newCall(getReq).execute().use { res ->
      if (res.isSuccessful) {
        val body    = gson.fromJson(res.body?.string(), Map::class.java)
        val content = String(Base64.getMimeDecoder().decode(body["content"] as String))
        Pair(gson.fromJson<MutableList<String>>(content, listType), body["sha"] as String?)
      } else {
        Pair(mutableListOf(), null)
      }
    }

    if (index.contains(username)) return
    index.add(username); index.sort()

    val encoded = Base64.getEncoder().encodeToString(gson.toJson(index).toByteArray())
    val bodyMap = mutableMapOf("message" to "ghostline: register $username [skip ci]", "content" to encoded)
    if (sha != null) bodyMap["sha"] = sha

    client.newCall(Request.Builder()
      .url("https://api.github.com/repos/$owner/$repo/contents/data/index.json")
      .header("Authorization", "token $token")
      .header("Accept", "application/vnd.github.v3+json")
      .put(gson.toJson(bodyMap).toRequestBody(JSON))
      .build()).execute().use { putRes ->
      if (!putRes.isSuccessful) {
        if (putRes.code == 422 && retries > 0)
          ensureInIndex(token, owner, repo, username, retries - 1)
        else
          throw RuntimeException("Index registration failed: HTTP ${putRes.code}")
      }
    }
  }

  private fun readFile(token: String, owner: String, repo: String, path: String): Pair<DevData?, String?> {
    val req = Request.Builder()
      .url("https://api.github.com/repos/$owner/$repo/contents/$path")
      .header("Authorization", "token $token")
      .header("Accept", "application/vnd.github.v3+json")
      .get().build()

    client.newCall(req).execute().use { res ->
      if (!res.isSuccessful) return Pair(null, null)
      val body    = gson.fromJson(res.body?.string(), Map::class.java)
      val content = String(Base64.getMimeDecoder().decode(body["content"] as String))
      val sha     = body["sha"] as String
      val type    = object : TypeToken<DevData>() {}.type
      return Pair(gson.fromJson(content, type), sha)
    }
  }

  private fun writeFile(token: String, owner: String, repo: String, path: String, data: DevData, sha: String?) {
    val content = Base64.getEncoder().encodeToString(gson.toJson(data).toByteArray())
    val bodyMap = mutableMapOf("message" to "ghostline: sync ${data.username} [skip ci]", "content" to content)
    if (sha != null) bodyMap["sha"] = sha

    client.newCall(Request.Builder()
      .url("https://api.github.com/repos/$owner/$repo/contents/$path")
      .header("Authorization", "token $token")
      .header("Accept", "application/vnd.github.v3+json")
      .put(gson.toJson(bodyMap).toRequestBody(JSON))
      .build()).execute().use { res ->
      if (!res.isSuccessful)
        throw RuntimeException("GitHub write failed: HTTP ${res.code}")
    }
  }
}
