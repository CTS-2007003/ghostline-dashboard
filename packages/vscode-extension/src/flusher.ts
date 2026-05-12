import * as vscode from 'vscode'
import { Octokit } from '@octokit/rest'
import { getTotalLines, getDevLines, getTestLines, getSession, resetSession } from './tracker'
import { readAndResetSessionFile } from './workspace'
import {
  writeLocalStatsDelta, readPending, writePending, clearPending, nowIso
} from './localStats'

export type FlushResult = 'synced' | 'nothing' | 'no-config'

interface HistoryEntry {
  date: string
  total: number
  ai: number
  dev?: number
  test?: number
}

interface DevData {
  username: string
  display_name: string
  team: string
  total_lines_written: number
  total_ai_lines: number
  dev_lines_written: number
  test_lines_written: number
  history: HistoryEntry[]
  last_updated: string
}

function today(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function config() {
  const cfg = vscode.workspace.getConfiguration('ghostline')
  return {
    repo:        cfg.get<string>('githubRepo', ''),
    username:    cfg.get<string>('githubUsername', ''),
    displayName: cfg.get<string>('displayName', ''),
    team:        cfg.get<string>('team', '')
  }
}

async function readFile(octokit: Octokit, owner: string, repo: string, path: string) {
  try {
    const res = await octokit.repos.getContent({ owner, repo, path })
    const file = res.data as { content: string; sha: string }
    return { content: JSON.parse(Buffer.from(file.content, 'base64').toString('utf-8')), sha: file.sha }
  } catch {
    return { content: null, sha: undefined }
  }
}

let syncing = false

/**
 * Flush local stats + push to GitHub.
 * - Always writes local stats first (data safe even if GitHub fails).
 * - Merges any pending data from a previous failed sync.
 * - Writes pending.json before the GitHub call so a failure is retried next open.
 * - Returns 'synced' | 'nothing' | 'no-config'. Throws on network error.
 */
export async function flush(context: vscode.ExtensionContext): Promise<FlushResult> {
  if (syncing) return 'nothing'
  syncing = true

  try {
    const sessionMap = getSession()
    const totalSnap  = getTotalLines()
    const devSnap    = getDevLines()
    const testSnap   = getTestLines()

    const folders = vscode.workspace.workspaceFolders
    const aiLines = folders?.reduce((sum, f) =>
      sum + readAndResetSessionFile(f.uri.fsPath), 0) ?? 0

    // Always persist local stats first — data is safe even if GitHub push fails
    writeLocalStatsDelta(sessionMap)
    resetSession()

    const { repo, username, displayName, team } = config()
    if (!repo || !username) return 'no-config'

    const token = await context.secrets.get('ghostline.githubToken')
    if (!token) return 'no-config'

    const effectiveTotal = Math.max(totalSnap, aiLines)
    const existingPending = readPending()

    // Nothing new in this session and no backlog — skip GitHub call
    if (effectiveTotal === 0 && !existingPending) return 'nothing'

    // Merge current session with any previously failed pending sync so nothing is lost
    const combined = {
      date:  today(),
      total: effectiveTotal        + (existingPending?.total ?? 0),
      ai:    aiLines               + (existingPending?.ai    ?? 0),
      dev:   devSnap               + (existingPending?.dev   ?? 0),
      test:  testSnap              + (existingPending?.test  ?? 0)
    }

    // Write pending BEFORE the GitHub call — if the network fails, this gets retried on next open
    writePending(combined)

    const [owner, repoName] = repo.split('/')
    const octokit  = new Octokit({ auth: token })
    const filePath = `data/${username}.json`

    const { content: existing, sha } = await readFile(octokit, owner, repoName, filePath)

    const current: DevData = existing ?? {
      username,
      display_name:       displayName || username,
      team:               team || '',
      total_lines_written: 0,
      total_ai_lines:     0,
      dev_lines_written:  0,
      test_lines_written: 0,
      history:            [],
      last_updated:       ''
    }

    if (displayName) current.display_name = displayName
    if (team)        current.team = team

    current.total_lines_written  += combined.total
    current.total_ai_lines       += combined.ai
    current.dev_lines_written     = (current.dev_lines_written  || 0) + combined.dev
    current.test_lines_written    = (current.test_lines_written || 0) + combined.test
    current.last_updated          = nowIso()

    const todayStr     = today()
    const historyEntry = current.history.find(h => h.date === todayStr)
    if (historyEntry) {
      historyEntry.total += combined.total
      historyEntry.ai    += combined.ai
      historyEntry.dev    = (historyEntry.dev  ?? 0) + combined.dev
      historyEntry.test   = (historyEntry.test ?? 0) + combined.test
    } else {
      current.history.push({
        date: todayStr, total: combined.total, ai: combined.ai,
        dev: combined.dev, test: combined.test
      })
    }
    current.history = current.history.slice(-90)

    await octokit.repos.createOrUpdateFileContents({
      owner, repo: repoName, path: filePath,
      message: `ghostline: sync ${username} [skip ci]`,
      content: Buffer.from(JSON.stringify(current, null, 2)).toString('base64'),
      sha
    })

    await ensureInIndex(octokit, owner, repoName, username)

    // Only clear pending after confirmed success
    clearPending()
    return 'synced'
  } catch (e) {
    // pending.json is preserved — will retry on next open
    throw e
  } finally {
    syncing = false
  }
}

async function ensureInIndex(octokit: Octokit, owner: string, repo: string, username: string) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const { content: existing, sha } = await readFile(octokit, owner, repo, 'data/index.json')
      const index: string[] = existing ?? []
      if (index.includes(username)) return
      index.push(username)
      index.sort()
      await octokit.repos.createOrUpdateFileContents({
        owner, repo, path: 'data/index.json',
        message: `ghostline: register ${username} [skip ci]`,
        content: Buffer.from(JSON.stringify(index, null, 2)).toString('base64'),
        sha
      })
      return
    } catch (e: any) {
      if (attempt === 0 && e?.status === 422) continue
      return
    }
  }
}
