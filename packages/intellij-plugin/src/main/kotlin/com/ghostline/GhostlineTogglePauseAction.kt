package com.ghostline

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

class GhostlineTogglePauseAction : AnAction() {

  override fun actionPerformed(e: AnActionEvent) {
    val session = SessionStore.getInstance()
    session.paused = !session.paused
    val msg = if (session.paused) "Tracking paused." else "Tracking resumed."
    NotificationGroupManager.getInstance()
      .getNotificationGroup("Ghostline")
      .createNotification("Ghostline", msg, NotificationType.INFORMATION)
      .notify(e.project)
  }

  // Update the menu label dynamically so it always reflects the current state
  override fun update(e: AnActionEvent) {
    val paused = SessionStore.getInstance().paused
    e.presentation.text = if (paused) "Resume Tracking" else "Pause Tracking"
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
