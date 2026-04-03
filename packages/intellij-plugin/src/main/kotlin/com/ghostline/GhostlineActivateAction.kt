package com.ghostline

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages

class GhostlineActivateAction : AnAction("Ghostline: Activate") {
  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return

    // Re-inject workspace instructions in case they were missed at startup
    WorkspaceInstructor.setup(project)

    // Re-run onboarding if not configured
    OnboardingService.checkAndPrompt(project)

    val settings = GhostlineSettings.getInstance()
    val token = AuthManager.getInstance().getToken()

    if (settings.githubRepo.isNotBlank() && settings.githubUsername.isNotBlank() && token != null) {
      Messages.showInfoMessage(
        "Ghostline is active and tracking.\n\nRepo: ${settings.githubRepo}\nUser: ${settings.githubUsername}",
        "Ghostline"
      )
    }
  }
}
