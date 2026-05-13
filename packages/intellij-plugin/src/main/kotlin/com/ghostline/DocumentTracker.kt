package com.ghostline

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.vfs.VirtualFile

@Service(Service.Level.PROJECT)
class DocumentTracker(private val project: Project) : FileEditorManagerListener, Disposable {

  // Map from file URL to (document, listener) so we can remove the listener on close.
  private val tracked = mutableMapOf<String, Pair<Document, DocumentListener>>()

  init {
    // Subscribe to future file open/close events via the project message bus.
    project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)

    // Attach listeners to files already open at startup — fileOpened may have fired
    // before this service was instantiated (restored tabs on IDE restart).
    StartupManager.getInstance(project).runAfterOpened {
      val fem = FileEditorManager.getInstance(project)
      fem.openFiles.forEach { file -> fileOpened(fem, file) }
    }
  }

  override fun dispose() {
    // Remove all document listeners on project close to avoid leaks.
    for ((_, pair) in tracked) pair.first.removeDocumentListener(pair.second)
    tracked.clear()
  }

  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    if (file.path.contains("/.ghostline/")) return  // skip local stats files
    if (tracked.containsKey(file.url)) return        // already listening on this document
    if (!file.isValid || file.isDirectory) return

    // FileDocumentManager works for background tabs, startup-restored tabs,
    // and any case where the file isn't the active tab yet.
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return

    val ext    = ".${file.extension ?: "unknown"}"
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
    fun getInstance(project: Project): DocumentTracker = project.service()

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
