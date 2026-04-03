import * as vscode from 'vscode'
import { startTracking, flagNextChangeAsAi } from './tracker'
import { flush } from './flusher'
import { setToken } from './auth'
import { runOnboardingIfNeeded } from './onboarding'

let flushTimer: NodeJS.Timeout | undefined
let statusBar: vscode.StatusBarItem

export async function activate(context: vscode.ExtensionContext) {
  statusBar = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Right, 100)
  statusBar.text = '$(ghost) Ghostline'
  statusBar.tooltip = 'Ghostline: AI line tracker active'
  statusBar.command = 'ghostline.showStatus'
  statusBar.show()
  context.subscriptions.push(statusBar)

  // Register commands
  context.subscriptions.push(
    vscode.commands.registerCommand('ghostline.setToken', () => setToken(context)),
    vscode.commands.registerCommand('ghostline.showStatus', showStatus),
    // This command is bound to Tab (when inlineSuggestionVisible) via package.json keybindings.
    // It flags the next document change as AI, then lets VS Code commit the inline suggestion.
    vscode.commands.registerCommand('ghostline.acceptAiSuggestion', async () => {
      flagNextChangeAsAi()
      await vscode.commands.executeCommand('editor.action.inlineSuggest.commit')
    })
  )

  // Start tracking document changes
  startTracking(context)

  // Run first-time setup wizard if not yet configured (non-blocking)
  runOnboardingIfNeeded(context)

  // Flush on a timer
  const intervalMs = vscode.workspace.getConfiguration('ghostline').get<number>('flushIntervalMinutes', 5) * 60 * 1000
  flushTimer = setInterval(() => flush(context), intervalMs)

  // Flush when window loses focus (user switches away / closes laptop)
  context.subscriptions.push(
    vscode.window.onDidChangeWindowState(state => {
      if (!state.focused) flush(context)
    })
  )
}

export function deactivate(context?: vscode.ExtensionContext) {
  if (flushTimer) clearInterval(flushTimer)
  if (context) flush(context)
}

function showStatus() {
  const { getSession } = require('./tracker') as typeof import('./tracker')
  const s = getSession()
  const humanLines = s.totalLines - s.aiLines
  const aiPct = s.totalLines > 0 ? Math.round((s.aiLines / s.totalLines) * 100) : 0
  vscode.window.showInformationMessage(
    `Ghostline — This session: ${s.totalLines} lines total | AI: ${s.aiLines} (${aiPct}%) | Human: ${humanLines}`
  )
}
