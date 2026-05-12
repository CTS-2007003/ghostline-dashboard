import * as vscode from 'vscode'
import { startTracking, getTotalLines, getDevLines, getTestLines, getSession } from './tracker'
import { flush } from './flusher'
import { setToken } from './auth'
import { runOnboardingIfNeeded } from './onboarding'
import { setupWorkspace } from './workspace'
import { writeLocalStatsDelta, readPending } from './localStats'

let statusBar: vscode.StatusBarItem
let storedContext: vscode.ExtensionContext | undefined
let writtenSnapshot = new Map<string, { dev: number; test: number }>()

export async function activate(context: vscode.ExtensionContext) {
  storedContext = context

  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100)
  statusBar.text = '$(ghost) Ghostline'
  statusBar.tooltip = 'Ghostline: click to sync now'
  statusBar.command = 'ghostline.sync'
  statusBar.show()
  context.subscriptions.push(statusBar)

  context.subscriptions.push(
    vscode.commands.registerCommand('ghostline.setToken',  () => setToken(context)),
    vscode.commands.registerCommand('ghostline.showStatus', showStatus),
    vscode.commands.registerCommand('ghostline.sync',      () => syncManual(context)),
    vscode.commands.registerCommand('ghostline.activate',  () => {
      vscode.window.showInformationMessage('Ghostline is active and tracking.')
      runOnboardingIfNeeded(context)
    })
  )

  setupWorkspace(context)
  startTracking(context)
  runOnboardingIfNeeded(context)

  // Update status bar every 30s with live counts
  const statusTimer = setInterval(updateStatusBar, 30_000)
  context.subscriptions.push({ dispose: () => clearInterval(statusTimer) })

  // Every 15 min — write local stats only (crash safety net, no network)
  const localTimer = setInterval(flushLocal, 15 * 60 * 1000)
  context.subscriptions.push({ dispose: () => clearInterval(localTimer) })

  // On open — silently retry any pending sync from last session (30s delay lets IDE settle)
  const retryTimeout = setTimeout(() => retryPending(context), 30_000)
  context.subscriptions.push({ dispose: () => clearTimeout(retryTimeout) })
}

export async function deactivate() {
  if (storedContext) {
    try {
      flushLocal()           // write any unsaved delta to local stats first
      await flush(storedContext)
    } catch {
      // pending.json preserved — retried on next open
    } finally {
      writtenSnapshot.clear()  // session is always reset inside flush()
    }
  }
}

// ── Local-only write (no network) ─────────────────────────────────────────────

function flushLocal() {
  const current      = getSession()
  const delta        = new Map<string, { dev: number; test: number }>()
  const nextSnapshot = new Map(writtenSnapshot)

  for (const [ext, lines] of current) {
    const written = writtenSnapshot.get(ext) ?? { dev: 0, test: 0 }
    const dDev  = lines.dev  - written.dev
    const dTest = lines.test - written.test
    if (dDev > 0 || dTest > 0) {
      delta.set(ext, { dev: Math.max(0, dDev), test: Math.max(0, dTest) })
      nextSnapshot.set(ext, { dev: lines.dev, test: lines.test })
    }
  }

  try {
    writeLocalStatsDelta(delta)
    writtenSnapshot = nextSnapshot  // only advance on success
  } catch {
    // keep writtenSnapshot unchanged so next tick retries
  }
}

// ── Pending retry (silent, on open) ──────────────────────────────────────────

async function retryPending(context: vscode.ExtensionContext) {
  if (!readPending()) return
  try {
    await flush(context)
  } catch {
    // still failing — pending.json preserved, retry next open
  } finally {
    writtenSnapshot.clear()  // session is always reset inside flush()
  }
}

// ── Manual sync (with user-facing notifications) ──────────────────────────────

async function syncManual(context: vscode.ExtensionContext) {
  statusBar.text = '$(loading~spin) Ghostline'
  try {
    flushLocal()  // write unsaved delta to local stats before GitHub push
    const result = await flush(context)
    if (result === 'synced') {
      vscode.window.showInformationMessage('Ghostline: Synced to dashboard ✓')
    } else if (result === 'nothing') {
      vscode.window.showInformationMessage('Ghostline: Nothing new to sync.')
    } else {
      vscode.window.showWarningMessage(
        'Ghostline: Set GitHub repo, username, and token in settings first.'
      )
    }
  } catch (e: any) {
    vscode.window.showErrorMessage(
      `Ghostline: Sync failed — ${e?.message ?? 'network error'}. Will retry on next open.`
    )
  } finally {
    writtenSnapshot.clear()  // session is always reset inside flush()
    updateStatusBar()
  }
}

// ── Status bar ────────────────────────────────────────────────────────────────

function updateStatusBar() {
  const dev   = getDevLines()
  const test  = getTestLines()
  const total = dev + test
  if (total === 0) {
    statusBar.text    = '$(ghost) Ghostline'
    statusBar.tooltip = 'Ghostline: click to sync now'
  } else {
    statusBar.text    = `$(ghost) ${total} lines (${dev} dev / ${test} test)`
    statusBar.tooltip = `Ghostline: ${total} lines this session — click to sync`
  }
}

function showStatus() {
  const dev   = getDevLines()
  const test  = getTestLines()
  const total = dev + test
  const pending = readPending()
  const parts = [`${total} lines this session (${dev} dev, ${test} test).`]
  if (pending) parts.push(`Pending retry: ${pending.total} lines from previous session.`)
  parts.push('Syncs automatically on close. Click the status bar to sync now.')
  vscode.window.showInformationMessage('Ghostline — ' + parts.join(' '))
}
