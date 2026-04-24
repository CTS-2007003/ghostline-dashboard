import * as vscode from 'vscode'
import { startTracking, getSession, resetSession, setPaused, isPaused } from './tracker'
import { flush } from './flusher'
import { setToken } from './auth'
import { runOnboardingIfNeeded } from './onboarding'
import { setupWorkspace } from './workspace'

let flushTimer: NodeJS.Timeout | undefined
let statusBar: vscode.StatusBarItem
let storedContext: vscode.ExtensionContext | undefined

export async function activate(context: vscode.ExtensionContext) {
  storedContext = context

  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100)
  statusBar.command = 'ghostline.showStatus'
  updateStatusBar()
  statusBar.show()
  context.subscriptions.push(statusBar)

  context.subscriptions.push(
    vscode.commands.registerCommand('ghostline.setToken', () => setToken(context)),
    vscode.commands.registerCommand('ghostline.showStatus', showStatus),
    vscode.commands.registerCommand('ghostline.activate', () => {
      vscode.window.showInformationMessage('Ghostline is active and tracking.')
      runOnboardingIfNeeded(context)
    }),

    // Pause / Resume — stops counting keystrokes, useful during refactors or large pastes
    vscode.commands.registerCommand('ghostline.togglePause', () => {
      setPaused(!isPaused())
      updateStatusBar()
      vscode.window.showInformationMessage(
        isPaused() ? 'Ghostline: Tracking paused.' : 'Ghostline: Tracking resumed.'
      )
    }),

    // Reset session — discards the unsaved in-memory count without touching GitHub
    vscode.commands.registerCommand('ghostline.resetSession', () => {
      const discarded = getSession().totalLines
      resetSession()
      vscode.window.showInformationMessage(
        `Ghostline: Session reset — ${discarded} unsaved lines discarded.`
      )
    }),

    // Flush now — push current counts immediately without waiting for the timer
    vscode.commands.registerCommand('ghostline.flushNow', async () => {
      if (storedContext) {
        await flush(storedContext)
        vscode.window.showInformationMessage('Ghostline: Sync triggered.')
      }
    })
  )

  setupWorkspace(context)
  startTracking(context)
  runOnboardingIfNeeded(context)

  const intervalMs = vscode.workspace.getConfiguration('ghostline')
    .get<number>('flushIntervalMinutes', 5) * 60 * 1000
  flushTimer = setInterval(() => flush(context), intervalMs)

  context.subscriptions.push(
    vscode.window.onDidChangeWindowState(state => {
      if (!state.focused) flush(context)
    })
  )
}

export async function deactivate() {
  if (flushTimer) clearInterval(flushTimer)
  if (storedContext) await flush(storedContext, true)
}

function updateStatusBar() {
  if (isPaused()) {
    statusBar.text = '$(ghost) Ghostline ⏸'
    statusBar.tooltip = 'Ghostline: Tracking paused — click for status'
  } else {
    statusBar.text = '$(ghost) Ghostline'
    statusBar.tooltip = 'Ghostline: AI line tracker active — click for status'
  }
}

function showStatus() {
  const s = getSession()
  const state = isPaused() ? ' ⏸ paused' : ''
  vscode.window.showInformationMessage(
    `Ghostline${state} — This session: ${s.totalLines} total lines written`
  )
}
