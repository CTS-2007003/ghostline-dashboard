package com.ghostline

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AppLifecycleHandler : AppLifecycleListener {
  private val log = Logger.getInstance(AppLifecycleHandler::class.java)
  private var scheduledFlush: ScheduledFuture<*>? = null
  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  override fun appStarted() {
    val intervalMinutes = GhostlineSettings.getInstance().flushIntervalMinutes.toLong()
    log.info("Ghostline: timer started — flushing every $intervalMinutes minute(s)")
    scheduledFlush = scheduler.scheduleAtFixedRate(
      {
        log.info("Ghostline: timer tick")
        GitHubFlusher.flush()
      },
      intervalMinutes,
      intervalMinutes,
      TimeUnit.MINUTES
    )
  }

  override fun appWillBeClosed(isRestart: Boolean) {
    scheduledFlush?.cancel(false)
    scheduler.shutdown()

    // Run flush on a dedicated thread — OkHttp calls must NOT run on the EDT.
    // Blocking the EDT during shutdown causes IntelliJ to cancel the operation.
    // Join with a 15s timeout so we don't hang the IDE close indefinitely.
    val thread = Thread({ GitHubFlusher.flush() }, "ghostline-close-flush")
    thread.start()
    thread.join(15_000)
  }
}
