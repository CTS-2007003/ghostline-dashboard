package com.ghostline

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager

class GhostlineFlushNowAction : AnAction("Flush Now") {

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    // OkHttp must not run on the EDT — push to a background thread
    ApplicationManager.getApplication().executeOnPooledThread {
      GitHubFlusher.flush()
      // Use null (app-level) if the project was closed while the flush was running
      val target = project?.takeIf { !it.isDisposed }
      NotificationGroupManager.getInstance()
        .getNotificationGroup("Ghostline")
        .createNotification("Ghostline", "Sync triggered.", NotificationType.INFORMATION)
        .notify(target)
    }
  }
}
