import * as vscode from 'vscode'

export interface Session {
  totalLines: number
}

const session: Session = { totalLines: 0 }

export function startTracking(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.workspace.onDidChangeTextDocument(event => {
      if (event.document.uri.scheme !== 'file') return

      for (const change of event.contentChanges) {
        const linesAdded = change.text.split('\n').length - 1
        const linesRemoved = change.range.end.line - change.range.start.line
        const net = linesAdded - linesRemoved
        if (net > 0) session.totalLines += net
      }
    })
  )
}

export function getSession(): Readonly<Session> {
  return { ...session }
}

export function resetSession() {
  session.totalLines = 0
}
