package com.ghostline

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class GhostlineConfigurable : Configurable {
  private val settings = GhostlineSettings.getInstance()
  private var panel: DialogPanel? = null

  override fun getDisplayName() = "Ghostline"

  override fun createComponent(): JComponent {
    val detectedUsername = OnboardingService.detectGitUsername()

    panel = panel {
      group("GitHub Connection") {
        row("Dashboard repo (owner/repo):") {
          textField()
            .bindText(settings::githubRepo)
            .columns(30)
            .comment("e.g. myorg/ghostline-dashboard")
        }
        row("GitHub username:") {
          textField()
            .bindText(settings::githubUsername)
            .columns(20)
            .applyToComponent {
              if (text.isBlank() && detectedUsername != null) text = detectedUsername
            }
        }
        row {
          button("Set GitHub Token") {
            val token = Messages.showPasswordDialog(
              null,
              "Paste your GitHub Personal Access Token.\n\nRequired: Contents (Read & Write) on \"${settings.githubRepo}\".",
              "Ghostline: Set Token",
              null
            )
            if (!token.isNullOrBlank()) {
              AuthManager.getInstance().saveToken(token)
              Messages.showInfoMessage("Token saved securely.", "Ghostline")
            }
          }
          button("Validate Connection") {
            val token = AuthManager.getInstance().getToken()
            val repo = settings.githubRepo.trim()
            when {
              token == null -> Messages.showErrorDialog("No token set. Click 'Set GitHub Token' first.", "Ghostline")
              repo.isBlank() -> Messages.showErrorDialog("Enter the dashboard repo first.", "Ghostline")
              else -> {
                val error = OnboardingService.validateAccess(token, repo)
                if (error == null) {
                  val (owner, repoName) = repo.split("/")
                  Messages.showInfoMessage(
                    "Connection successful!\nDashboard: https://$owner.github.io/$repoName",
                    "Ghostline"
                  )
                } else {
                  Messages.showErrorDialog(error, "Ghostline: Validation Failed")
                }
              }
            }
          }
        }
      }
      group("Sync Settings") {
        row("Flush interval (minutes):") {
          intTextField(1..60)
            .bindIntText(settings::flushIntervalMinutes)
            .comment("How often to push your line counts to GitHub")
        }
      }
    }

    return panel!!
  }

  override fun isModified() = panel?.isModified() ?: false

  override fun apply() {
    panel?.apply()
  }

  override fun reset() {
    panel?.reset()
  }
}
