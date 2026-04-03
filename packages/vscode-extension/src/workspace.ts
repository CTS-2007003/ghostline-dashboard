import * as vscode from 'vscode'
import * as fs from 'fs'
import * as path from 'path'

const GHOSTLINE_INSTRUCTION = `
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
`

const SESSION_FILE = '.ghostline/session.json'

// AI context files — keyed by filename, value is the section marker
const AI_CONTEXT_FILES: Record<string, string> = {
  '.gemini/context.md': '<!-- ghostline -->',
  '.github/copilot-instructions.md': '<!-- ghostline -->',
  'CLAUDE.md': '<!-- ghostline -->',
  '.cursorrules': '# ghostline'
}

export function setupWorkspace(context: vscode.ExtensionContext) {
  const folders = vscode.workspace.workspaceFolders
  if (!folders?.length) return

  for (const folder of folders) {
    const root = folder.uri.fsPath
    injectInstructions(root)
    resetSessionFile(root)
    watchSessionFile(root, context)
  }

  // Handle newly opened folders
  context.subscriptions.push(
    vscode.workspace.onDidChangeWorkspaceFolders(e => {
      for (const folder of e.added) {
        const root = folder.uri.fsPath
        injectInstructions(root)
        resetSessionFile(root)
        watchSessionFile(root, context)
      }
    })
  )
}

function injectInstructions(root: string) {
  for (const [file, marker] of Object.entries(AI_CONTEXT_FILES)) {
    const filePath = path.join(root, file)
    const dir = path.dirname(filePath)

    try {
      // Ensure directory exists
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })

      let existing = ''
      if (fs.existsSync(filePath)) {
        existing = fs.readFileSync(filePath, 'utf-8')
        // Already injected — skip
        if (existing.includes(marker)) continue
      }

      const injection = `\n${marker}\n${GHOSTLINE_INSTRUCTION.trim()}\n${marker}\n`
      fs.writeFileSync(filePath, existing + injection, 'utf-8')
    } catch {
      // Skip files we can't write (permissions etc.)
    }
  }
}

function resetSessionFile(root: string) {
  const dir = path.join(root, '.ghostline')
  const filePath = path.join(dir, 'session.json')
  try {
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(filePath, JSON.stringify({ ai_lines: 0 }, null, 2))
  } catch {}
}

export function readAndResetSessionFile(root: string): number {
  const filePath = path.join(root, SESSION_FILE)
  try {
    if (!fs.existsSync(filePath)) return 0
    const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'))
    const lines = data.ai_lines ?? 0
    // Reset for next flush cycle
    fs.writeFileSync(filePath, JSON.stringify({ ai_lines: 0 }, null, 2))
    return lines
  } catch {
    return 0
  }
}

function watchSessionFile(root: string, context: vscode.ExtensionContext) {
  const filePath = path.join(root, SESSION_FILE)
  const watcher = vscode.workspace.createFileSystemWatcher(
    new vscode.RelativePattern(root, SESSION_FILE)
  )
  // No action needed on change — flusher reads it on flush
  context.subscriptions.push(watcher)
}
