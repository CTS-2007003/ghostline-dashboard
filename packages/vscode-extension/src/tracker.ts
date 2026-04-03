import * as vscode from 'vscode'

export interface Session {
  totalLines: number
  aiLines: number
}

const session: Session = { totalLines: 0, aiLines: 0 }

// Tracks whether the last change event was triggered by an inline completion acceptance.
// We toggle this flag by wrapping the known VS Code inline suggest commit command.
let pendingAiAcceptance = false

export function flagNextChangeAsAi() {
  pendingAiAcceptance = true
}

export function startTracking(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.workspace.onDidChangeTextDocument(event => {
      if (event.document.uri.scheme !== 'file') return

      for (const change of event.contentChanges) {
        const linesAdded = change.text.split('\n').length - 1
        const linesRemoved = change.range.end.line - change.range.start.line
        const net = linesAdded - linesRemoved

        if (net <= 0) continue

        session.totalLines += net

        if (pendingAiAcceptance) {
          session.aiLines += net
        }
      }

      // Reset after processing — one document change per acceptance
      if (pendingAiAcceptance) {
        pendingAiAcceptance = false
      }
    })
  )
}

export function getSession(): Readonly<Session> {
  return { ...session }
}

export function resetSession() {
  session.totalLines = 0
  session.aiLines = 0
}
