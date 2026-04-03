package com.ghostline

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager

/**
 * Listens for AI inline completion accept actions from any provider
 * (Gemini Code Assist, JetBrains AI, GitHub Copilot, etc.) and sets
 * AiAcceptanceFlag before the document change fires.
 *
 * Detection strategy (two independent signals, either is enough):
 *
 * 1. Platform check — IntelliJ 2023.2+ exposes InlineCompletion.getInstanceOrNull(editor).
 *    If a session is active, whatever Tab does next is an AI acceptance. Provider-agnostic.
 *
 * 2. Action ID / class-name check — catches providers on older IntelliJ builds
 *    or providers that manage their own suggestion UI outside the platform API.
 */
object AiActionInterceptor {

  private val AI_ACCEPT_ACTION_IDS = setOf(
    "InsertInlineCompletion",                          // JetBrains AI
    "com.google.cloudcode.actions.AcceptInline",       // Gemini Code Assist (full id)
    "cloudcode.acceptInlineCompletion",                // Gemini Code Assist (short id)
    "editor.action.inlineSuggest.commit",              // Generic fallback
    "copilot.applyInlayHint"                           // GitHub Copilot
  )

  fun register() {
    ApplicationManager.getApplication().messageBus
      .connect()
      .subscribe(AnActionListener.TOPIC, object : AnActionListener {
        override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
          if (hasPlatformInlineSession(event) || isKnownAiAcceptAction(action)) {
            AiAcceptanceFlag.set()
          }
        }
      })
  }

  /**
   * Provider-agnostic check: ask the IntelliJ inline completion platform
   * whether any provider currently has an active suggestion in this editor.
   * Works for every AI tool that uses the standard InlineCompletionProvider API.
   */
  private fun hasPlatformInlineSession(event: AnActionEvent): Boolean {
    val editor = event.getData(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR)
      ?: return false
    return try {
      val inlineCompletionClass = Class.forName("com.intellij.codeInsight.inline.completion.InlineCompletion")
      val getInstance = inlineCompletionClass.getMethod("getInstanceOrNull", Editor::class.java)
      getInstance.invoke(null, editor) != null
    } catch (_: Exception) {
      // IntelliJ < 2023.2 or class moved — fall through to action ID check
      false
    }
  }

  /**
   * Fallback: match by registered action ID or class name.
   * Catches providers that use their own suggestion UI instead of the platform API.
   */
  private fun isKnownAiAcceptAction(action: AnAction): Boolean {
    val id = ActionManager.getInstance().getId(action)
    if (id != null && id in AI_ACCEPT_ACTION_IDS) return true

    val className = action.javaClass.simpleName.lowercase()
    return (className.contains("inlinecompletion") && className.contains("accept")) ||
           (className.contains("inlinesuggest") && className.contains("commit"))
  }
}
