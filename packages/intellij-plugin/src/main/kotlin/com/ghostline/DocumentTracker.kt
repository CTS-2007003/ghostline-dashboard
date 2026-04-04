package com.ghostline

import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.vfs.VirtualFile

class DocumentTracker : FileEditorManagerListener {

  // Track which document URLs have already had a listener attached
  private val tracked = mutableSetOf<String>()

  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    if (file.path.contains("/.ghostline/")) return  // skip session/instruction files
    if (!tracked.add(file.url)) return  // already listening on this document
    val editor = source.getSelectedEditor(file) ?: return
    val document = (editor as? com.intellij.openapi.fileEditor.TextEditor)?.editor?.document ?: return

    document.addDocumentListener(object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        val linesAdded = event.newFragment.count { it == '\n' }
        val linesRemoved = event.oldFragment.count { it == '\n' }
        val net = linesAdded - linesRemoved
        if (net > 0) SessionStore.getInstance().totalLines += net
      }
    })
  }

  override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
    tracked.remove(file.url)
  }

  override fun selectionChanged(event: FileEditorManagerEvent) {}
}
