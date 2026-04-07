import * as vscode from 'vscode'
import { startTracking, getSession } from './tracker'
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
  statusBar.text = '$(ghost) Ghostline'
  statusBar.tooltip = 'Ghostline: AI line tracker active'
  statusBar.command = 'ghostline.showStatus'
  statusBar.show()
  context.subscriptions.push(statusBar)

  context.subscriptions.push(
    vscode.commands.registerCommand('ghostline.setToken', () => setToken(context)),
    vscode.commands.registerCommand('ghostline.showStatus', showStatus),
    vscode.commands.registerCommand('ghostline.activate', () => {
      vscode.window.showInformationMessage('Ghostline is active and tracking.')
      runOnboardingIfNeeded(context)
    })
  )

  // Inject AI instruction files + reset session.json for this session
  setupWorkspace(context)

  // Track total lines written
  startTracking(context)

  // First-time setup wizard
  runOnboardingIfNeeded(context)

  // Flush on a timer
  const intervalMs = vscode.workspace.getConfiguration('ghostline')
    .get<number>('flushIntervalMinutes', 5) * 60 * 1000
  flushTimer = setInterval(() => flush(context), intervalMs)

  // Flush when window loses focus
  context.subscriptions.push(
    vscode.window.onDidChangeWindowState(state => {
      if (!state.focused) flush(context)
    })
  )
}

export async function deactivate() {
  if (flushTimer) clearInterval(flushTimer)
  if (storedContext) await flush(storedContext)
}

function showStatus() {
  const s = getSession()
  vscode.window.showInformationMessage(
    `Ghostline — This session: ${s.totalLines} total lines written`
  )
}
