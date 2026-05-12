package com.ghostline

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// var fields required — Gson on Java 17 cannot reliably set val (final) fields via
// Unsafe.putObject, so deserialization would silently leave them at 0.
data class ExtLines(var dev: Int = 0, var test: Int = 0)

data class LocalStats(
  var last_updated: String = "",
  var by_extension: MutableMap<String, ExtLines> = mutableMapOf()
)

data class PendingSync(
  var date:  String = "",
  var total: Int    = 0,
  var ai:    Int    = 0,
  var dev:   Int    = 0,
  var test:  Int    = 0
)

object LocalStatsWriter {
  private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
  private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

  private fun ghostlineDir(): File {
    val dir = File(System.getProperty("user.home"), ".ghostline")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  private fun statsFile()   = File(ghostlineDir(), "stats.json")
  private fun pendingFile() = File(ghostlineDir(), "pending.json")

  private fun read(): LocalStats {
    val file = statsFile()
    if (!file.exists()) return LocalStats()
    return try {
      val type = object : TypeToken<LocalStats>() {}.type
      gson.fromJson(file.readText(), type) ?: LocalStats()
    } catch (_: Exception) { LocalStats() }
  }

  /** Thread-safe atomic merge of a per-extension delta into the cumulative stats file. */
  @Synchronized
  fun writeDelta(delta: Map<String, ExtLines>) {
    if (delta.isEmpty()) return

    val stats = read()
    for ((ext, lines) in delta) {
      val cur = stats.by_extension[ext] ?: ExtLines(0, 0)
      stats.by_extension[ext] = ExtLines(cur.dev + lines.dev, cur.test + lines.test)
    }
    stats.last_updated = ZonedDateTime.now().format(fmt)

    atomicWrite(statsFile(), gson.toJson(stats), "stats.json.ij.tmp")
  }

  // ── Pending sync ─────────────────────────────────────────────────────────────

  fun readPending(): PendingSync? {
    val file = pendingFile()
    if (!file.exists()) return null
    return try {
      gson.fromJson(file.readText(), PendingSync::class.java)
    } catch (_: Exception) { null }
  }

  @Synchronized
  fun writePending(p: PendingSync) {
    pendingFile().writeText(gson.toJson(p))
  }

  @Synchronized
  fun clearPending() {
    pendingFile().delete()
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private fun atomicWrite(target: File, content: String, tmpName: String) {
    val tmp = File(target.parentFile, tmpName)
    tmp.writeText(content)
    // Files.move with REPLACE_EXISTING is reliable on Windows unlike File.renameTo()
    try {
      Files.move(tmp.toPath(), target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE)
    } catch (_: Exception) {
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
