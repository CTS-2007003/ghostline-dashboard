package com.ghostline

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.util.concurrent.ConcurrentHashMap

@Service
class SessionStore {
  // Lines typed since the last GitHub sync, keyed by file extension.
  // ConcurrentHashMap so DocumentTracker (EDT) and flush (background) can
  // access it concurrently without explicit locking.
  val byExt: ConcurrentHashMap<String, ExtLines> = ConcurrentHashMap()

  fun addLines(ext: String, dev: Int, test: Int) {
    byExt.compute(ext) { _, cur ->
      ExtLines(
        dev = (cur?.dev ?: 0) + dev,
        test = (cur?.test ?: 0) + test
      )
    }
  }

  fun totalLines(): Int = byExt.values.sumOf { it.dev + it.test }
  fun devLines(): Int = byExt.values.sumOf { it.dev }
  fun testLines(): Int = byExt.values.sumOf { it.test }

  fun snapshot(): Map<String, ExtLines> = HashMap(byExt)

  fun reset() {
    byExt.clear()
  }

  companion object {
    fun getInstance(): SessionStore = service()
  }
}
