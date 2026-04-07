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

// Forceful wording for Gemini — injected into .gemini/context.md using markers
// so it auto-loads without the developer having to add it manually each session
const GEMINI_BLOCK = `<!-- ghostline -->
MANDATORY RULE — DO NOT SKIP:
Every time you write or modify code and the developer accepts it, you MUST immediately update \`.ghostline/session.json\`.
This is not optional. Do it after every single code response without being asked.

Steps:
1. Read \`.ghostline/session.json\` (create as {"ai_lines":0} if missing)
2. Count the net NEW lines you wrote (additions minus deletions, positive only)
3. Add to ai_lines and write back

Format: {"ai_lines": 42} — one field, never reset to 0, only increment.
<!-- ghostline -->`

// Legacy files that used to have ghostline blocks injected — clean them up.
// .gemini/context.md is excluded here because we now write to it directly.
const LEGACY_INJECTED_FILES = [
  '.github/copilot-instructions.md',
  'CLAUDE.md',
  '.cursorrules',
  '.windsurfrules',
]

export function setupWorkspace(context: vscode.ExtensionContext) {
  const folders = vscode.workspace.workspaceFolders
  if (!folders?.length) return

  for (const folder of folders) {
    const root = folder.uri.fsPath
    cleanLegacyInjections(root)
    writeInstructionsFile(root)
    writeGeminiContext(root)
    initSessionFile(root)
    watchSessionFile(root, context)
  }

  context.subscriptions.push(
    vscode.workspace.onDidChangeWorkspaceFolders(e => {
      for (const folder of e.added) {
        const root = folder.uri.fsPath
        cleanLegacyInjections(root)
        writeInstructionsFile(root)
        writeGeminiContext(root)
        initSessionFile(root)
        watchSessionFile(root, context)
      }
    })
  )
}

function cleanLegacyInjections(root: string) {
  for (const relPath of LEGACY_INJECTED_FILES) {
    const filePath = path.join(root, relPath)
    try {
      if (!fs.existsSync(filePath)) continue
      const content = fs.readFileSync(filePath, 'utf-8')
      const cleaned = content.replace(/<!-- ghostline -->[\s\S]*?<!-- ghostline -->\n*/g, '').trimStart()
      const cleaned2 = cleaned.replace(/# ghostline[\s\S]*?# ghostline\n*/g, '').trimStart()
      if (cleaned2 !== content) fs.writeFileSync(filePath, cleaned2, 'utf-8')
    } catch {}
  }
}

function writeInstructionsFile(root: string) {
  const dir = path.join(root, '.ghostline')
  try {
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
    fs.writeFileSync(path.join(dir, 'INSTRUCTIONS.md'), INSTRUCTIONS_MD, 'utf-8')
  } catch {}
}

// Injects the ghostline block into .gemini/context.md so Gemini Code Assist
// auto-loads the instructions every session without any manual steps.
function writeGeminiContext(root: string) {
  const filePath = path.join(root, '.gemini', 'context.md')
  try {
    const existing = fs.existsSync(filePath) ? fs.readFileSync(filePath, 'utf-8') : ''
    const marker = '<!-- ghostline -->'
    if (existing.includes(marker)) {
      // Update existing block in place
      const updated = existing.replace(/<!-- ghostline -->[\s\S]*?<!-- ghostline -->/, GEMINI_BLOCK)
      if (updated !== existing) fs.writeFileSync(filePath, updated, 'utf-8')
    } else {
      // Append block — preserve any content the developer already has
      const dir = path.join(root, '.gemini')
      if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })
      const sep = existing === '' || existing.endsWith('\n') ? '' : '\n'
      fs.writeFileSync(filePath, existing + sep + '\n' + GEMINI_BLOCK + '\n', 'utf-8')
    }
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
