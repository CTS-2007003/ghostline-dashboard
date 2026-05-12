package com.ghostline

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task

class GhostlineSyncAction : AnAction() {
  private val log = Logger.getInstance(GhostlineSyncAction::class.java)

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project

    // Validation before kicking off the background task
    val settings = GhostlineSettings.getInstance()
    if (settings.githubRepo.isBlank() || settings.githubUsername.isBlank()) {
      notify(project, "Set 'githubRepo' and 'githubUsername' in Settings → Ghostline first.", NotificationType.WARNING)
      return
    }
    if (AuthManager.getInstance().getToken() == null) {
      notify(project, "No token found. Open Settings → Ghostline and click 'Set GitHub Token'.", NotificationType.WARNING)
      return
    }

    // Snapshot total before the background task — flush() resets the session,
    // so we'd read 0 if we checked after.
    val totalBefore = SessionStore.getInstance().totalLines()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Ghostline: Syncing to dashboard…", false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        try {
          GitHubFlusher.flush()
          val msg = if (totalBefore == 0)
            "Nothing new to sync (no lines typed since last sync)."
          else
            "Synced $totalBefore lines to dashboard."
          notify(project, msg, NotificationType.INFORMATION)
        } catch (t: Throwable) {
          // flush() re-throws on GitHub error — surface it to the user
          notify(project, "Sync failed: ${t.message ?: t.javaClass.simpleName}", NotificationType.ERROR)
        }
      }
    })
  }

  private fun notify(project: com.intellij.openapi.project.Project?, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Ghostline")
      .createNotification(message, type)
      .notify(project)
  }
}
