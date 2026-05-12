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
// Unsafe.putObject, so deserialization would silently leave them at 0, and the next
// write would wipe the existing stats with zeroed values.
data class ExtLines(var dev: Int = 0, var test: Int = 0)

data class LocalStats(
  var last_updated: String = "",
  var by_extension: MutableMap<String, ExtLines> = mutableMapOf()
)

object LocalStatsWriter {
  private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
  private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

  private fun statsFile(): File {
    val dir = File(System.getProperty("user.home"), ".ghostline")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "stats.json")
  }

  private fun read(): LocalStats {
    val file = statsFile()
    if (!file.exists()) return LocalStats()
    return try {
      val type = object : TypeToken<LocalStats>() {}.type
      gson.fromJson(file.readText(), type) ?: LocalStats()
    } catch (_: Exception) {
      LocalStats()
    }
  }

  /** Thread-safe atomic merge of a per-extension delta into the cumulative stats file. */
  @Synchronized
  fun writeDelta(delta: Map<String, ExtLines>) {
    if (delta.isEmpty()) return

    val stats = read()

    for ((ext, lines) in delta) {
      val cur = stats.by_extension[ext] ?: ExtLines(0, 0)
      stats.by_extension[ext] = ExtLines(
        dev = cur.dev + lines.dev,
        test = cur.test + lines.test
      )
    }

    stats.last_updated = ZonedDateTime.now().format(fmt)

    val file = statsFile()
    // Use a process-specific temp name so a concurrent VS Code write
    // (which uses stats.json.vscode.tmp) can't overwrite our temp file mid-rename.
    val tmp = File(file.parentFile, "stats.json.ij.tmp")
    tmp.writeText(gson.toJson(stats))

    // Files.move with REPLACE_EXISTING + ATOMIC_MOVE is the reliable cross-platform
    // rename on Java — File.renameTo() silently returns false on Windows when the
    // target already exists and another process has it open.
    try {
      Files.move(tmp.toPath(), file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE)
    } catch (_: Exception) {
      // ATOMIC_MOVE not supported on all filesystems (e.g. cross-device); fall back
      Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }
}
