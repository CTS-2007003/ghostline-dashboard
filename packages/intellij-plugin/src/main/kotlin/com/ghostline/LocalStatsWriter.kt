package com.ghostline

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class ExtLines(val dev: Int, val test: Int)

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
    val tmp = File(file.parentFile, "stats.json.tmp")
    tmp.writeText(gson.toJson(stats))
    // Atomic rename — safe for concurrent VS Code + IntelliJ writes on the same machine
    tmp.renameTo(file)
  }
}
