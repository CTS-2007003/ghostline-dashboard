import * as vscode from 'vscode'
import { startTracking, getTotalLines, getDevLines, getTestLines, getSession, resetSession } from './tracker'
import { flush } from './flusher'
import { setToken } from './auth'
import { runOnboardingIfNeeded } from './onboarding'
import { setupWorkspace } from './workspace'
import { writeLocalStatsDelta } from './localStats'

let statusBar: vscode.StatusBarItem
let storedContext: vscode.ExtensionContext | undefined

export async function activate(context: vscode.ExtensionContext) {
  storedContext = context

  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100)
  statusBar.text = '$(ghost) Ghostline'
  statusBar.tooltip = 'Ghostline: click to sync to dashboard'
  statusBar.command = 'ghostline.sync'
  statusBar.show()
  context.subscriptions.push(statusBar)

  context.subscriptions.push(
    vscode.commands.registerCommand('ghostline.setToken', () => setToken(context)),
    vscode.commands.registerCommand('ghostline.showStatus', showStatus),
    vscode.commands.registerCommand('ghostline.sync', () => syncToDashboard(context)),
    vscode.commands.registerCommand('ghostline.activate', () => {
      vscode.window.showInformationMessage('Ghostline is active and tracking.')
      runOnboardingIfNeeded(context)
    })
  )

  // Inject AI instruction files for this workspace
  setupWorkspace(context)

  // Track lines written per file extension
  startTracking(context)

  // First-time setup wizard
  runOnboardingIfNeeded(context)

  // Update status bar every 30s with live counts
  const statusTimer = setInterval(updateStatusBar, 30_000)
  context.subscriptions.push({ dispose: () => clearInterval(statusTimer) })

  // Flush window loses focus — write local stats so numbers aren't lost
  context.subscriptions.push(
    vscode.window.onDidChangeWindowState(state => {
      if (!state.focused) flushLocal()
    })
  )
}

export async function deactivate() {
  // Write any unsaved in-memory counts to local stats on exit
  flushLocal()
}

/** Persist in-memory delta to ~/.ghostline/stats.json without resetting it.
 *  Safe to call multiple times — the delta is always "what we haven't written yet"
 *  because we track a separate writtenSnapshot. */
let writtenSnapshot = new Map<string, { dev: number; test: number }>()

function flushLocal() {
  const current = getSession()
  const delta = new Map<string, { dev: number; test: number }>()
  // Compute the new snapshot separately — only commit it if the write succeeds.
  // If we updated writtenSnapshot before the write and the write throws, the next
  // flushLocal() would see zero delta and silently lose those lines.
  const nextSnapshot = new Map(writtenSnapshot)

  for (const [ext, lines] of current) {
    const written = writtenSnapshot.get(ext) ?? { dev: 0, test: 0 }
    const dDev = lines.dev - written.dev
    const dTest = lines.test - written.test
    if (dDev > 0 || dTest > 0) {
      delta.set(ext, { dev: Math.max(0, dDev), test: Math.max(0, dTest) })
      nextSnapshot.set(ext, { dev: lines.dev, test: lines.test })
    }
  }

  try {
    writeLocalStatsDelta(delta)
    writtenSnapshot = nextSnapshot  // only advance snapshot on successful write
  } catch {
    // write failed (disk full, locked) — keep writtenSnapshot unchanged so next call retries
  }
}

async function syncToDashboard(context: vscode.ExtensionContext) {
  statusBar.text = '$(loading~spin) Ghostline'
  try {
    await flush(context)
    // After sync, in-memory was reset — also reset writtenSnapshot so local
    // stats won't try to subtract negative values on the next flushLocal
    writtenSnapshot.clear()
  } finally {
    updateStatusBar()
  }
}

function updateStatusBar() {
  const dev = getDevLines()
  const test = getTestLines()
  const total = dev + test
  if (total === 0) {
    statusBar.text = '$(ghost) Ghostline'
    statusBar.tooltip = 'Ghostline: click to sync to dashboard'
  } else {
    statusBar.text = `$(ghost) ${total} lines (${dev} dev / ${test} test)`
    statusBar.tooltip = `Ghostline: ${total} lines this session — click to sync`
  }
}

function showStatus() {
  const dev = getDevLines()
  const test = getTestLines()
  const total = dev + test
  vscode.window.showInformationMessage(
    `Ghostline — This session: ${total} lines written (${dev} dev, ${test} test). ` +
    `Click the status bar or run "Ghostline: Sync to Dashboard" to push to GitHub.`
  )
}
