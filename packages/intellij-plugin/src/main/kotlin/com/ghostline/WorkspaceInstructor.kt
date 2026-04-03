package com.ghostline

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

object WorkspaceInstructor {

  private val INSTRUCTION = """

## Ghostline AI Tracking

This project tracks AI-generated vs human-written lines of code.

Your responsibility: each time you generate code the developer accepts,
update .ghostline/session.json by adding the net new lines you wrote.

Rules:
- Read the current value first, then add your new lines to it
- Never reset the count — only ever increment it
- If .ghostline/session.json does not exist, create it as {"ai_lines": 0}
- Count net positive lines only (additions minus deletions)
- Count all accepted lines — inline completions AND chat insertions
""".trimIndent()

  // AI context files and their section markers
  private val AI_CONTEXT_FILES = mapOf(
    ".gemini/context.md" to "<!-- ghostline -->",
    ".github/copilot-instructions.md" to "<!-- ghostline -->",
    "CLAUDE.md" to "<!-- ghostline -->",
    ".cursorrules" to "# ghostline"
  )

  fun setup(project: Project) {
    val root = project.basePath ?: return
    injectInstructions(root)
    resetSessionFile(root)
  }

  private fun injectInstructions(root: String) {
    for ((relativePath, marker) in AI_CONTEXT_FILES) {
      val file = File(root, relativePath)
      try {
        file.parentFile.mkdirs()
        val existing = if (file.exists()) file.readText() else ""
        if (existing.contains(marker)) continue  // already injected

        val injection = "\n$marker\n$INSTRUCTION\n$marker\n"
        file.writeText(existing + injection)

        // Refresh VFS so IntelliJ sees the new file
        LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
      } catch (_: Exception) {
        // Skip files we can't write
      }
    }
  }

  fun resetSessionFile(root: String) {
    val dir = File(root, ".ghostline")
    val file = File(dir, "session.json")
    try {
      dir.mkdirs()
      file.writeText("""{"ai_lines": 0}""")
    } catch (_: Exception) {}
  }

  fun readAndResetSessionFile(root: String): Int {
    val file = File(root, ".ghostline/session.json")
    return try {
      if (!file.exists()) return 0
      val content = file.readText()
      val match = Regex(""""ai_lines"\s*:\s*(\d+)""").find(content)
      val lines = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
      file.writeText("""{"ai_lines": 0}""")
      lines
    } catch (_: Exception) {
      0
    }
  }
}
