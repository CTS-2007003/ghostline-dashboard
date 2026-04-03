package com.ghostline

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class GhostlineConfigurable : Configurable {
  private val settings = GhostlineSettings.getInstance()
  private var repoField: JBTextField? = null
  private var usernameField: JBTextField? = null
  private var intervalField: JBTextField? = null
  private var rootPanel: JComponent? = null

  override fun getDisplayName() = "Ghostline"

  override fun createComponent(): JComponent {
    val detectedUsername = OnboardingService.detectGitUsername()

    rootPanel = panel {
      group("GitHub Connection") {
        row("Dashboard repo (owner/repo):") {
          textField()
            .columns(30)
            .comment("e.g. myorg/ghostline-dashboard")
            .applyToComponent {
              text = settings.githubRepo
              repoField = this
            }
        }
        row("GitHub username:") {
          textField()
            .columns(20)
            .applyToComponent {
              text = settings.githubUsername.ifBlank { detectedUsername ?: "" }
              usernameField = this
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
            val repo = repoField?.text?.trim() ?: ""
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
          textField()
            .columns(5)
            .comment("How often to push your line counts to GitHub (1–60)")
            .applyToComponent {
              text = settings.flushIntervalMinutes.toString()
              intervalField = this
            }
        }
      }
    }

    return rootPanel!!
  }

  override fun isModified(): Boolean {
    return repoField?.text?.trim() != settings.githubRepo ||
      usernameField?.text?.trim() != settings.githubUsername ||
      intervalField?.text?.toIntOrNull() != settings.flushIntervalMinutes
  }

  override fun apply() {
    settings.githubRepo = repoField?.text?.trim() ?: ""
    settings.githubUsername = usernameField?.text?.trim() ?: ""
    settings.flushIntervalMinutes = intervalField?.text?.toIntOrNull()?.coerceIn(1, 60) ?: 5
  }

  override fun reset() {
    repoField?.text = settings.githubRepo
    usernameField?.text = settings.githubUsername
    intervalField?.text = settings.flushIntervalMinutes.toString()
  }
}
