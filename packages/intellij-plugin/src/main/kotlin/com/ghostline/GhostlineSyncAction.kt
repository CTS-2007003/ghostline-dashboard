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

    val session = SessionStore.getInstance()
    val total = session.totalLines()

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Ghostline: Syncing to dashboard…", false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        try {
          GitHubFlusher.flush()

          val msg = if (total == 0)
            "Nothing new to sync."
          else
            "Synced ${total} lines to dashboard. Check https://github.com/${settings.githubRepo}"

          notify(project, msg, NotificationType.INFORMATION)
        } catch (t: Throwable) {
          log.error("Ghostline: sync failed", t)
          notify(project, "Sync failed: ${t.message}", NotificationType.ERROR)
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
