package com.ghostline

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.vfs.VirtualFile

class DocumentTracker : FileEditorManagerListener {

  // Map from file URL to (document, listener) so we can remove the listener on close.
  // Without this, IntelliJ reuses the cached Document object on reopen, leaving the
  // old listener attached — every subsequent keystroke would be counted twice.
  private val tracked = mutableMapOf<String, Pair<Document, DocumentListener>>()

  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    if (file.path.contains("/.ghostline/")) return  // skip local stats files
    if (tracked.containsKey(file.url)) return        // already listening on this document

    val editor = source.getSelectedEditor(file) ?: return
    val document = (editor as? com.intellij.openapi.fileEditor.TextEditor)?.editor?.document ?: return

    val ext = ".${file.extension ?: "unknown"}"
    val isTest = isTestFile(file.nameWithoutExtension)

    val listener = object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        val linesAdded   = event.newFragment.count { it == '\n' }
        val linesRemoved = event.oldFragment.count { it == '\n' }
        val net = linesAdded - linesRemoved
        if (net > 0) {
          if (isTest) {
            SessionStore.getInstance().addLines(ext, dev = 0, test = net)
          } else {
            SessionStore.getInstance().addLines(ext, dev = net, test = 0)
          }
        }
      }
    }

    document.addDocumentListener(listener)
    tracked[file.url] = Pair(document, listener)
  }

  override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
    tracked.remove(file.url)?.let { (document, listener) ->
      document.removeDocumentListener(listener)
    }
  }

  override fun selectionChanged(event: FileEditorManagerEvent) {}

  companion object {
    /** Returns true if the filename (without extension) indicates a test file,
     *  based on the comma-separated patterns in Settings → Ghostline → Test file patterns. */
    fun isTestFile(nameWithoutExtension: String): Boolean {
      val n = nameWithoutExtension.lowercase()
      val raw = GhostlineSettings.getInstance().testPatterns
      val patterns = raw.split(",")
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
      return patterns.any { n.contains(it) }
    }
  }
}
