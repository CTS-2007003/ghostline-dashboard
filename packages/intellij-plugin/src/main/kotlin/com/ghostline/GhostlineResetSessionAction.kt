package com.ghostline

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class GhostlineResetSessionAction : AnAction("Reset Session") {

  override fun actionPerformed(e: AnActionEvent) {
    val session = SessionStore.getInstance()
    val discarded = session.totalLines
    session.reset()
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Ghostline")
      .createNotification(
        "Ghostline",
        "Session reset — $discarded unsaved lines discarded.",
        NotificationType.INFORMATION
      )
      .notify(e.project)
  }
}
