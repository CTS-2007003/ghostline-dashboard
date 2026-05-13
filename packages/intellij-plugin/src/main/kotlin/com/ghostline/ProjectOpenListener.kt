package com.ghostline

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

class ProjectOpenListener : ProjectManagerListener {
  override fun projectOpened(project: Project) {
    // Force DocumentTracker instantiation — it self-registers as FileEditorManagerListener
    // and scans already-open files in its init block via StartupManager.runAfterOpened.
    DocumentTracker.getInstance(project)

    WorkspaceInstructor.setup(project)
    OnboardingService.checkAndPrompt(project)
  }
}
