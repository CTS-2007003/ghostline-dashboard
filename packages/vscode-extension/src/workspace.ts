import * as vscode from 'vscode'
import * as fs from 'fs'
import * as path from 'path'

const INSTRUCTIONS_MD = `# Ghostline — AI Line Tracking (REQUIRED)

This file is managed by the Ghostline extension. Do not edit manually.

---

After **every response** in which you write or modify code, you MUST update \`.ghostline/session.json\`.
Do NOT wait to be asked. Do NOT skip this. Every single code response, no exceptions.

## Steps

1. Read \`.ghostline/session.json\` — create it as \`{"ai_lines": 0}\` if it doesn't exist
2. Count net lines you wrote: additions minus deletions, positive only
3. Add that number to \`ai_lines\` and write the file back

## What counts
- All code you generated that the developer accepted
- Inline completions and chat-based insertions both count
- Rewrites: count the new lines

## What does NOT count
- Lines the developer typed themselves
- Blank lines you didn't produce
- Deleted lines

## Format
\`\`\`json
{"ai_lines": 42}
\`\`\`
One field only. Never reset to 0 — only ever increment.

## Example
You write 12 lines. Developer accepts.
- Read \`{"ai_lines": 30}\` → add 12 → write \`{"ai_lines": 42}\`
`

const INSTRUCTIONS_FILE = '.ghostline/INSTRUCTIONS.md'
const SESSION_FILE = '.ghostline/session.json'


export function setupWorkspace(context: vscode.ExtensionContext) {
  const folders = vscode.workspace.workspaceFolders
  if (!folders?.length) return

  for (const folder of folders) {
    const root = folder.uri.fsPath
    writeInstructionsFile(root)
    initSessionFile(root)
    watchSessionFile(root, context)
  }

  context.subscriptions.push(
    vscode.workspace.onDidChangeWorkspaceFolders(e => {
      for (const folder of e.added) {
        const root = folder.uri.fsPath
        writeInstructionsFile(root)
        initSessionFile(root)
        watchSessionFile(root, context)
      }
    })
  )
}

function writeInstructionsFile(root: string) {
  const dir = path.join(root, '.ghostline')
  try {
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(path.join(dir, 'INSTRUCTIONS.md'), INSTRUCTIONS_MD, 'utf-8')
  } catch {}
}

// Only creates session.json if it doesn't already exist — never resets AI lines on startup
function initSessionFile(root: string) {
  const filePath = path.join(root, SESSION_FILE)
  try {
    if (fs.existsSync(filePath)) return
    const dir = path.join(root, '.ghostline')
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(filePath, JSON.stringify({ ai_lines: 0 }, null, 2))
  } catch {}
}

// Watches session.json for deletion and immediately recreates it so mid-session
// deletes (manual or via git) don't permanently lose the AI line count slot.
function watchSessionFile(root: string, context: vscode.ExtensionContext) {
  const watcher = vscode.workspace.createFileSystemWatcher(
    new vscode.RelativePattern(root, SESSION_FILE)
  )
  watcher.onDidDelete(() => initSessionFile(root))
  context.subscriptions.push(watcher)
}

export function readAndResetSessionFile(root: string): number {
  const filePath = path.join(root, SESSION_FILE)
  try {
    if (!fs.existsSync(filePath)) return 0
    const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'))
    const lines = data.ai_lines ?? 0
    fs.writeFileSync(filePath, JSON.stringify({ ai_lines: 0 }, null, 2))
    return lines
  } catch {
    return 0
  }
}
