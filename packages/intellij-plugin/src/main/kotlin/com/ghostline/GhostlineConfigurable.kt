package com.ghostline

import com.intellij.openapi.application.ApplicationManager
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
  private var displayNameField: JBTextField? = null
  private var teamField: JBTextField? = null
  private var testPatternsField: JBTextField? = null
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
        row("Display name:") {
          textField()
            .columns(20)
            .comment("Name shown on the team dashboard (your real name or nickname)")
            .applyToComponent {
              text = settings.displayName.ifBlank { detectedUsername ?: "" }
              displayNameField = this
            }
        }
        row("Team:") {
          textField()
            .columns(20)
            .comment("Your team name (e.g. Frontend, Backend, Mobile)")
            .applyToComponent {
              text = settings.team
              teamField = this
            }
        }
        row {
          button("Set GitHub Token") {
            val token = Messages.showPasswordDialog(
              null,
              "Paste your shared GitHub Personal Access Token.\n\n" +
              "Required scope: public_repo (classic token).\n" +
              "Get this token from your team lead.",
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
          button("Sync Now") {
            Thread({
              try {
                GitHubFlusher.flushLocal()   // persist unsaved delta before push
                when (GitHubFlusher.flush()) {
                  FlushResult.SYNCED    -> ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage("Synced to dashboard ✓", "Ghostline")
                  }
                  FlushResult.NOTHING   -> ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage("Nothing new to sync.", "Ghostline")
                  }
                  FlushResult.NO_CONFIG -> ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog("Set GitHub repo, username, and token first.", "Ghostline")
                  }
                }
              } catch (t: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                  Messages.showErrorDialog(
                    "Sync failed: ${t.message ?: "network error"}. Will retry on next open.",
                    "Ghostline"
                  )
                }
              }
            }, "ghostline-manual-sync").start()
          }
        }
      }
      group("Tracking") {
        row("Test file patterns:") {
          textField()
            .columns(30)
            .comment("Comma-separated words — files whose name contains any of these are counted as test lines (e.g. test,spec,e2e,integration)")
            .applyToComponent {
              text = settings.testPatterns
              testPatternsField = this
            }
        }
      }
      group("Local Stats") {
        row {
          label("Line counts are saved locally to ~/.ghostline/stats.json every 15 minutes.")
        }
        row {
          label("Syncs to GitHub automatically on close, or click Sync Now above.")
        }
      }
    }

    return rootPanel!!
  }

  override fun isModified(): Boolean {
    return repoField?.text?.trim() != settings.githubRepo ||
      usernameField?.text?.trim() != settings.githubUsername ||
      displayNameField?.text?.trim() != settings.displayName ||
      teamField?.text?.trim() != settings.team ||
      testPatternsField?.text?.trim() != settings.testPatterns
  }

  override fun apply() {
    settings.githubRepo = repoField?.text?.trim() ?: ""
    settings.githubUsername = usernameField?.text?.trim() ?: ""
    settings.displayName = displayNameField?.text?.trim() ?: ""
    settings.team = teamField?.text?.trim() ?: ""
    settings.testPatterns = testPatternsField?.text?.trim()?.ifBlank { "test,spec" } ?: "test,spec"
  }

  override fun reset() {
    repoField?.text = settings.githubRepo
    usernameField?.text = settings.githubUsername
    displayNameField?.text = settings.displayName
    teamField?.text = settings.team
    testPatternsField?.text = settings.testPatterns
  }
}
