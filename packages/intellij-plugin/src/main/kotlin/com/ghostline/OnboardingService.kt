package com.ghostline

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.ui.Messages
import com.intellij.util.net.ssl.CertificateManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader

object OnboardingService {

  fun checkAndPrompt(project: Project) {
    val settings = GhostlineSettings.getInstance()
    val token = AuthManager.getInstance().getToken()

    if (settings.githubRepo.isNotBlank() && settings.githubUsername.isNotBlank() && token != null) return

    NotificationGroupManager.getInstance()
      .getNotificationGroup("Ghostline")
      .createNotification(
        "Ghostline — AI Line Tracker",
        "Track AI vs human lines across your team. Only line counts are synced — no code, no filenames, nothing else.",
        NotificationType.INFORMATION
      )
      .addAction(com.intellij.notification.NotificationAction.createSimple("Configure Ghostline") {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, GhostlineConfigurable::class.java)
      })
      .notify(project)
  }

  /**
   * Validates the token has access to the configured repo.
   * Called from the settings UI when the user clicks "Validate & Save".
   * Returns null on success, an error message on failure.
   */
  fun validateAccess(token: String, repo: String): String? {
    val cm = CertificateManager.getInstance()
    val client = OkHttpClient.Builder()
      .sslSocketFactory(cm.sslContext.socketFactory, cm.trustManager)
      .build()
    val req = Request.Builder()
      .url("https://api.github.com/repos/$repo")
      .header("Authorization", "token $token")
      .header("Accept", "application/vnd.github.v3+json")
      .get().build()

    return try {
      val res = client.newCall(req).execute()
      when (res.code) {
        200 -> null
        401 -> "Token is invalid or expired."
        404 -> "Repo \"$repo\" not found. Check the name and token permissions."
        else -> "GitHub returned HTTP ${res.code}."
      }
    } catch (e: Exception) {
      "Could not reach GitHub: ${e.message}"
    }
  }

  /**
   * Tries to read the GitHub username from git config.
   * Falls back to null if git is not available.
   */
  fun detectGitUsername(): String? {
    return try {
      val proc = ProcessBuilder("git", "config", "--global", "user.name")
        .redirectErrorStream(true)
        .start()
      val output = BufferedReader(InputStreamReader(proc.inputStream)).readLine()?.trim()
      if (output.isNullOrBlank()) null else output
    } catch (_: Exception) {
      null
    }
  }
}
