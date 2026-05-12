import * as vscode from 'vscode'
import * as path from 'path'
import { ExtLines } from './localStats'

// In-memory session: lines typed since the last GitHub sync.
// Resets after a successful sync; persisted to ~/.ghostline/stats.json on
// sync and on IDE close so the local file is always up to date.
const session = new Map<string, ExtLines>()

function isTestFile(filePath: string): boolean {
  const base = path.basename(filePath, path.extname(filePath)).toLowerCase()
  return base.includes('test') || base.includes('spec')
}

export function startTracking(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.workspace.onDidChangeTextDocument(event => {
      if (event.document.uri.scheme !== 'file') return

      const filePath = event.document.uri.fsPath
      const ext = path.extname(filePath) || '.unknown'
      const isTest = isTestFile(filePath)

      for (const change of event.contentChanges) {
        const linesAdded = change.text.split('\n').length - 1
        const linesRemoved = change.range.end.line - change.range.start.line
        const net = linesAdded - linesRemoved
        if (net > 0) {
          const cur = session.get(ext) ?? { dev: 0, test: 0 }
          session.set(ext, isTest
            ? { ...cur, test: cur.test + net }
            : { ...cur, dev: cur.dev + net }
          )
        }
      }
    })
  )
}

/** Returns a snapshot copy of the current session — safe to iterate and pass around. */
export function getSession(): Map<string, ExtLines> {
  return new Map(session)
}

export function getTotalLines(): number {
  let total = 0
  for (const v of session.values()) total += v.dev + v.test
  return total
}

export function getDevLines(): number {
  let total = 0
  for (const v of session.values()) total += v.dev
  return total
}

export function getTestLines(): number {
  let total = 0
  for (const v of session.values()) total += v.test
  return total
}

export function resetSession() {
  session.clear()
}
