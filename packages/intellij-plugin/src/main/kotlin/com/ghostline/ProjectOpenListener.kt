package com.ghostline

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

class ProjectOpenListener : ProjectManagerListener {
  override fun projectOpened(project: Project) {
    WorkspaceInstructor.setup(project)
    OnboardingService.checkAndPrompt(project)
  }
}
