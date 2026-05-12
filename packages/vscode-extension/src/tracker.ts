import * as vscode from 'vscode'
import * as path from 'path'
import { ExtLines } from './localStats'

// In-memory session: lines typed since the last GitHub sync.
const session = new Map<string, ExtLines>()

function isTestFile(filePath: string): boolean {
  const base = path.basename(filePath, path.extname(filePath)).toLowerCase()
  const raw  = vscode.workspace.getConfiguration('ghostline')
    .get<string>('testPatterns', 'test,spec') || 'test,spec'
  const patterns = raw.split(',')
    .map(p => p.trim().toLowerCase())
    .filter(p => p.length > 0)
  return patterns.some(p => base.includes(p))
}

export function startTracking(context: vscode.ExtensionContext) {
  context.subscriptions.push(
    vscode.workspace.onDidChangeTextDocument(event => {
      if (event.document.uri.scheme !== 'file') return

      const filePath = event.document.uri.fsPath
      const ext      = path.extname(filePath) || '.unknown'
      const isTest   = isTestFile(filePath)

      for (const change of event.contentChanges) {
        const linesAdded   = change.text.split('\n').length - 1
        const linesRemoved = change.range.end.line - change.range.start.line
        const net = linesAdded - linesRemoved
        if (net > 0) {
          const cur = session.get(ext) ?? { dev: 0, test: 0 }
          session.set(ext, isTest
            ? { ...cur, test: cur.test + net }
            : { ...cur, dev:  cur.dev  + net }
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
  let t = 0; for (const v of session.values()) t += v.dev + v.test; return t
}

export function getDevLines(): number {
  let t = 0; for (const v of session.values()) t += v.dev;  return t
}

export function getTestLines(): number {
  let t = 0; for (const v of session.values()) t += v.test; return t
}

export function resetSession() {
  session.clear()
}
