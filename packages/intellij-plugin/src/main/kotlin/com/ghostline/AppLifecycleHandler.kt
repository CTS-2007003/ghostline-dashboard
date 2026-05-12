package com.ghostline

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.Logger

class AppLifecycleHandler : AppLifecycleListener {
  private val log = Logger.getInstance(AppLifecycleHandler::class.java)

  override fun appStarted() {
    log.info("Ghostline: started — tracking lines per extension. Use 'Ghostline: Sync to Dashboard' to push to GitHub.")
  }

  override fun appWillBeClosed(isRestart: Boolean) {
    // Write unsaved in-memory line counts to local stats on close.
    // Run on a dedicated thread — file I/O should not block the EDT.
    // Join with a 5s timeout to avoid hanging the IDE close.
    val thread = Thread({
      try {
        GitHubFlusher.flushLocal()
      } catch (t: Throwable) {
        log.error("Ghostline: error writing local stats on close", t)
      }
    }, "ghostline-close-local")
    thread.start()
    thread.join(5_000)
  }
}
