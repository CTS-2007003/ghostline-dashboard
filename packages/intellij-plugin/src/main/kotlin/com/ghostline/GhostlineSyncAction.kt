package com.ghostline

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

class GhostlineSyncAction : AnAction() {
  private val log = Logger.getInstance(GhostlineSyncAction::class.java)

  override fun actionPerformed(e: AnActionEvent) {
    val project  = e.project
    val settings = GhostlineSettings.getInstance()

    if (settings.githubRepo.isBlank() || settings.githubUsername.isBlank()) {
      notify(project, "Set 'Dashboard repo' and 'GitHub username' in Settings → Ghostline first.", NotificationType.WARNING)
      return
    }
    if (AuthManager.getInstance().getToken() == null) {
      notify(project, "No token found. Open Settings → Ghostline and click 'Set GitHub Token'.", NotificationType.WARNING)
      return
    }

    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Ghostline: Syncing…", false) {
      override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        try {
          GitHubFlusher.flushLocal()   // persist unsaved delta before push
          when (GitHubFlusher.flush()) {
            FlushResult.SYNCED    -> notify(project, "Synced to dashboard ✓", NotificationType.INFORMATION)
            FlushResult.NOTHING   -> notify(project, "Nothing new to sync.", NotificationType.INFORMATION)
            FlushResult.NO_CONFIG -> notify(project, "Set up GitHub repo and token in settings first.", NotificationType.WARNING)
          }
        } catch (t: Throwable) {
          log.error("Ghostline: manual sync failed", t)
          notify(project, "Sync failed: ${t.message ?: t.javaClass.simpleName}. Will retry on next open.", NotificationType.ERROR)
        }
      }
    })
  }

  private fun notify(project: Project?, message: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Ghostline")
      .createNotification(message, type)
      .notify(project)
  }
}
