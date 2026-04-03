package com.ghostline

import com.intellij.ide.AppLifecycleListener
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AppLifecycleHandler : AppLifecycleListener {
  private var scheduledFlush: ScheduledFuture<*>? = null
  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  override fun appStarted() {
    val intervalMinutes = GhostlineSettings.getInstance().flushIntervalMinutes.toLong()
    scheduledFlush = scheduler.scheduleAtFixedRate(
      { GitHubFlusher.flush() },
      intervalMinutes,
      intervalMinutes,
      TimeUnit.MINUTES
    )
  }

  override fun appWillBeClosed(isRestart: Boolean) {
    scheduledFlush?.cancel(false)
    scheduler.shutdown()
    GitHubFlusher.flush()
  }
}
