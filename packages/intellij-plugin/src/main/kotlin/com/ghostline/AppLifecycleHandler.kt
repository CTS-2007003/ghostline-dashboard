package com.ghostline

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AppLifecycleHandler : AppLifecycleListener {
  private val log = Logger.getInstance(AppLifecycleHandler::class.java)
  private val scheduler = Executors.newSingleThreadScheduledExecutor()
  private var localTimer: ScheduledFuture<*>? = null

  // Tracks what's already been written to local stats this session — same approach
  // as VS Code's writtenSnapshot to avoid double-counting on repeated 15-min writes.
  private val localWrittenSnapshot = ConcurrentHashMap<String, ExtLines>()

  override fun appStarted() {
    log.info("Ghostline: started — syncs automatically on close. Use Tools → Ghostline: Sync Now for immediate sync.")

    // 15-min local stats write (crash safety net — no network)
    localTimer = scheduler.scheduleAtFixedRate(
      {
        try { flushLocalDelta() }
        catch (t: Throwable) { log.warn("Ghostline: local write failed: ${t.message}") }
      },
      15, 15, TimeUnit.MINUTES
    )

    // Retry any pending sync from last session (30s delay lets IDE fully start)
    scheduler.schedule(
      {
        try { GitHubFlusher.pushPending() }
        catch (t: Throwable) { log.warn("Ghostline: pending retry on open failed: ${t.message}") }
      },
      30, TimeUnit.SECONDS
    )
  }

  override fun appWillBeClosed(isRestart: Boolean) {
    localTimer?.cancel(false)
    scheduler.shutdown()

    // Auto-sync on close — writes local stats + pushes to GitHub.
    // 15s timeout covers network latency; local stats written first so
    // data is never lost even if the GitHub push times out.
    val thread = Thread({
      try {
        GitHubFlusher.flush()
        localWrittenSnapshot.clear()
        log.info("Ghostline: auto-sync on close complete")
      } catch (t: Throwable) {
        log.warn("Ghostline: GitHub sync on close failed — pending.json saved for retry: ${t.message}")
      }
    }, "ghostline-close-sync")
    thread.start()
    thread.join(15_000)
  }

  /** Write the delta (lines typed since last local write) to ~/.ghostline/stats.json. */
  private fun flushLocalDelta() {
    val current = SessionStore.getInstance().snapshot()
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
}
