package com.ghostline

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

object WorkspaceInstructor {

  private val INSTRUCTIONS_MD = """
# Ghostline — AI Line Tracking (REQUIRED)

This file is managed by the Ghostline extension. Do not edit manually.

---

After **every response** in which you write or modify code, you MUST update `.ghostline/session.json`.
Do NOT wait to be asked. Do NOT skip this. Every single code response, no exceptions.

## Steps

1. Read `.ghostline/session.json` — create it as `{"ai_lines": 0}` if it doesn't exist
2. Count net lines you wrote: additions minus deletions, positive only
3. Add that number to `ai_lines` and write the file back

## What counts
- All code you generated that the developer accepted
- Inline completions and chat-based insertions both count
- Rewrites: count the new lines

## What does NOT count
- Lines the developer typed themselves
- Blank lines you didn't produce
- Deleted lines

## Format
```json
{"ai_lines": 42}
```
One field only. Never reset to 0 — only ever increment.

## Example
You write 12 lines. Developer accepts.
- Read `{"ai_lines": 30}` → add 12 → write `{"ai_lines": 42}`
""".trimIndent()

  private val LEGACY_FILES = listOf(
    ".gemini/context.md",
    ".github/copilot-instructions.md",
    "CLAUDE.md",
    ".cursorrules",
    ".windsurfrules",
  )

  fun setup(project: Project) {
    val root = project.basePath ?: return
    cleanLegacyInjections(root)
    writeInstructionsFile(root)
    initSessionFile(root)
    ensureGitignore(root)
    watchSessionFile(root)
  }

  private fun cleanLegacyInjections(root: String) {
    for (relativePath in LEGACY_FILES) {
      val file = File(root, relativePath)
      try {
        if (!file.exists()) continue
        val content = file.readText()
        // Remove <!-- ghostline --> blocks
        var cleaned = content.replace(Regex("<!-- ghostline -->[\\s\\S]*?<!-- ghostline -->\\n*"), "").trimStart()
        // Remove # ghostline blocks (old .cursorrules style)
        cleaned = cleaned.replace(Regex("# ghostline[\\s\\S]*?# ghostline\\n*"), "").trimStart()
        if (cleaned != content) {
          file.writeText(cleaned)
          LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
        }
      } catch (_: Exception) {}
    }
  }

  private fun writeInstructionsFile(root: String) {
    val dir = File(root, ".ghostline")
    val file = File(dir, "INSTRUCTIONS.md")
    try {
      dir.mkdirs()
      file.writeText(INSTRUCTIONS_MD)
      LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
    } catch (_: Exception) {}
  }

  // Only creates session.json if it doesn't already exist — never resets AI lines on startup
  fun initSessionFile(root: String) {
    val dir = File(root, ".ghostline")
    val file = File(dir, "session.json")
    try {
      if (file.exists()) return
      dir.mkdirs()
      file.writeText("""{"ai_lines": 0}""")
    } catch (_: Exception) {}
  }

  // Ensures only session.json is gitignored — NOT the whole .ghostline/ dir.
  // INSTRUCTIONS.md must stay visible so Gemini, Copilot, etc. can load it as context,
  // and so teammates get it when they clone the repo.
  private fun ensureGitignore(root: String) {
    val entry = ".ghostline/session.json"
    val oldEntry = ".ghostline/"
    val gitignore = File(root, ".gitignore")
    try {
      val existing = if (gitignore.exists()) gitignore.readText() else ""
      // Migrate: replace old broad ignore with the specific file entry
      if (existing.lines().any { it.trim() == oldEntry }) {
        val migrated = existing.lines()
          .map { if (it.trim() == oldEntry) entry else it }
          .joinToString("\n")
        gitignore.writeText(migrated)
        return
      }
      if (existing.lines().any { it.trim() == entry }) return
      val append = (if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n") + entry + "\n"
      gitignore.writeText(existing + append)
    } catch (_: Exception) {}
  }

  // Watches session.json via IntelliJ VFS — recreates it immediately if deleted mid-session
  // so git operations or manual deletes don't permanently lose the AI line count slot.
  private fun watchSessionFile(root: String) {
    val sessionPath = File(root, ".ghostline/session.json").absolutePath
    LocalFileSystem.getInstance().addVirtualFileListener(object : com.intellij.openapi.vfs.VirtualFileListener {
      override fun fileDeleted(event: com.intellij.openapi.vfs.VirtualFileEvent) {
        if (event.file.path == sessionPath) initSessionFile(root)
      }
    })
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
