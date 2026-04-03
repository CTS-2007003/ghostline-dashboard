package com.ghostline

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.columns
import javax.swing.JComponent
import javax.swing.JPanel

class GhostlineConfigurable : Configurable {
  private val settings = GhostlineSettings.getInstance()
  private var repoField: javax.swing.JTextField? = null
  private var usernameField: javax.swing.JTextField? = null
  private var intervalField: javax.swing.JSpinner? = null
  private var rootPanel: JPanel? = null

  override fun getDisplayName() = "Ghostline"

  override fun createComponent(): JComponent {
    val detectedUsername = OnboardingService.detectGitUsername()

    val p = panel {
      group("GitHub Connection") {
        row("Dashboard repo (owner/repo):") {
          textField()
            .bindText(settings::githubRepo)
            .columns(30)
            .also { repoField = it.component }
            .comment("e.g. myorg/ghostline-dashboard")
        }
        row("GitHub username:") {
          textField()
            .bindText(settings::githubUsername)
            .columns(20)
            .also { usernameField = it.component }
            .applyToComponent {
              if (text.isBlank() && detectedUsername != null) text = detectedUsername
            }
        }
        row {
          button("Set GitHub Token") {
            val token = Messages.showPasswordDialog(
              null,
              "Paste your GitHub Personal Access Token.\n\nRequired scope: Contents (Read & Write) on \"${settings.githubRepo}\".",
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
          spinner(1..60)
            .bindIntText(settings::flushIntervalMinutes)
            .also { intervalField = it.component as? javax.swing.JSpinner }
            .comment("How often to push your line counts to GitHub")
        }
      }
    }

    rootPanel = p
    return p
  }

  override fun isModified(): Boolean {
    return (rootPanel as? com.intellij.ui.dsl.builder.impl.DialogPanelImpl)?.isModified() ?: false
  }

  override fun apply() {
    (rootPanel as? com.intellij.ui.dsl.builder.impl.DialogPanelImpl)?.apply()
  }

  override fun reset() {
    (rootPanel as? com.intellij.ui.dsl.builder.impl.DialogPanelImpl)?.reset()
  }
}
