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

  // Forceful wording for Gemini — injected into .gemini/context.md using markers
  // so it auto-loads without the developer having to add it manually each session
  private val GEMINI_BLOCK = """
<!-- ghostline -->
MANDATORY RULE — DO NOT SKIP:
Every time you write or modify code and the developer accepts it, you MUST immediately update `.ghostline/session.json`.
This is not optional. Do it after every single code response without being asked.

Steps:
1. Read `.ghostline/session.json` (create as {"ai_lines":0} if missing)
2. Count the net NEW lines you wrote (additions minus deletions, positive only)
3. Add to ai_lines and write back

Format: {"ai_lines": 42} — one field, never reset to 0, only increment.
<!-- ghostline -->
""".trimIndent()

  // Legacy files that used to have ghostline blocks injected — clean them up.
  // .gemini/context.md is excluded here because we now write to it directly.
  private val LEGACY_FILES = listOf(
    ".github/copilot-instructions.md",
    "CLAUDE.md",
    ".cursorrules",
    ".windsurfrules",
  )

  fun setup(project: Project) {
    val root = project.basePath ?: return
    cleanLegacyInjections(root)
    writeInstructionsFile(root)
    writeGeminiContext(root)
    initSessionFile(root)
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

  // Injects the ghostline block into .gemini/context.md so Gemini Code Assist
  // auto-loads the instructions every session without any manual steps.
  private fun writeGeminiContext(root: String) {
    val file = File(root, ".gemini/context.md")
    try {
      val existing = if (file.exists()) file.readText() else ""
      val marker = "<!-- ghostline -->"
      if (existing.contains(marker)) {
        // Update existing block in place
        val updated = existing.replace(Regex("<!-- ghostline -->[\\s\\S]*?<!-- ghostline -->"), GEMINI_BLOCK)
        if (updated != existing) file.writeText(updated)
      } else {
        // Append block — preserve any content the developer already has
        val sep = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        file.parentFile?.mkdirs()
        file.writeText(existing + sep + "\n" + GEMINI_BLOCK + "\n")
      }
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
