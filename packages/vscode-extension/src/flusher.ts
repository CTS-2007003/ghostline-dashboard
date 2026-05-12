import * as vscode from 'vscode'
import { Octokit } from '@octokit/rest'
import { getTotalLines, getDevLines, getTestLines, getSession, resetSession } from './tracker'
import { readAndResetSessionFile } from './workspace'
import { writeLocalStatsDelta } from './localStats'

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
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function nowIso(): string {
  const now = new Date()
  const off = -now.getTimezoneOffset()
  const sign = off >= 0 ? '+' : '-'
  const pad2 = (n: number) => String(Math.abs(n)).padStart(2, '0')
  return (
    `${now.getFullYear()}-${pad2(now.getMonth() + 1)}-${pad2(now.getDate())}` +
    `T${pad2(now.getHours())}:${pad2(now.getMinutes())}:${pad2(now.getSeconds())}` +
    `${sign}${pad2(Math.floor(Math.abs(off) / 60))}:${pad2(Math.abs(off) % 60)}`
  )
}

function config() {
  const cfg = vscode.workspace.getConfiguration('ghostline')
  return {
    repo: cfg.get<string>('githubRepo', ''),
    username: cfg.get<string>('githubUsername', ''),
    displayName: cfg.get<string>('displayName', ''),
    team: cfg.get<string>('team', '')
  }
}

async function readFile(octokit: Octokit, owner: string, repo: string, path: string) {
  try {
    const res = await octokit.repos.getContent({ owner, repo, path })
    const file = res.data as { content: string; sha: string }
    const content = JSON.parse(Buffer.from(file.content, 'base64').toString('utf-8'))
    return { content, sha: file.sha }
  } catch {
    return { content: null, sha: undefined }
  }
}

// Prevents two concurrent syncs from double-counting lines
let syncing = false

/** Called manually by the "Sync to Dashboard" command. */
export async function flush(context: vscode.ExtensionContext, force = false) {
  if (syncing && !force) return
  syncing = true

  try {
    const { repo, username, displayName, team } = config()
    if (!repo || !username) {
      vscode.window.showWarningMessage(
        'Ghostline: Set "githubRepo" and "githubUsername" in settings before syncing.'
      )
      return
    }

    const token = await context.secrets.get('ghostline.githubToken')
    if (!token) {
      vscode.window.showWarningMessage(
        'Ghostline: No token found. Run "Ghostline: Set GitHub Token" first.'
      )
      return
    }

    // Snapshot in-memory before any async work
    const sessionMap = getSession()
    const totalSnap = getTotalLines()
    const devSnap = getDevLines()
    const testSnap = getTestLines()

    // Read AI lines from session.json (written by AI tools)
    const folders = vscode.workspace.workspaceFolders
    const aiLines = folders?.reduce((sum, f) =>
      sum + readAndResetSessionFile(f.uri.fsPath), 0) ?? 0

    if (totalSnap === 0 && aiLines === 0) {
      vscode.window.showInformationMessage('Ghostline: Nothing new to sync.')
      return
    }

    // Write delta to local stats BEFORE resetting in-memory
    writeLocalStatsDelta(sessionMap)

    // Reset in-memory immediately (before network — prevents double-count on concurrent sync)
    resetSession()

    const [owner, repoName] = repo.split('/')
    const octokit = new Octokit({ auth: token })
    const path = `data/${username}.json`

    const { content: existing, sha } = await readFile(octokit, owner, repoName, path)

    const effectiveTotal = Math.max(totalSnap, aiLines)

    const current: DevData = existing ?? {
      username,
      display_name: displayName || username,
      team: team || '',
      total_lines_written: 0,
      total_ai_lines: 0,
      dev_lines_written: 0,
      test_lines_written: 0,
      history: [],
      last_updated: ''
    }

    if (displayName) current.display_name = displayName
    if (team) current.team = team

    current.total_lines_written += effectiveTotal
    current.total_ai_lines += aiLines
    current.dev_lines_written = (current.dev_lines_written || 0) + devSnap
    current.test_lines_written = (current.test_lines_written || 0) + testSnap
    current.last_updated = nowIso()

    const todayStr = today()
    const historyEntry = current.history.find(h => h.date === todayStr)
    if (historyEntry) {
      historyEntry.total += effectiveTotal
      historyEntry.ai += aiLines
      historyEntry.dev = (historyEntry.dev ?? 0) + devSnap
      historyEntry.test = (historyEntry.test ?? 0) + testSnap
    } else {
      current.history.push({
        date: todayStr,
        total: effectiveTotal,
        ai: aiLines,
        dev: devSnap,
        test: testSnap
      })
    }

    current.history = current.history.slice(-90)

    await octokit.repos.createOrUpdateFileContents({
      owner,
      repo: repoName,
      path,
      message: `ghostline: sync ${username} [skip ci]`,
      content: Buffer.from(JSON.stringify(current, null, 2)).toString('base64'),
      sha
    })

    await ensureInIndex(octokit, owner, repoName, username)

    vscode.window.showInformationMessage(
      `Ghostline: Synced — ${effectiveTotal} total lines (${devSnap} dev, ${testSnap} test, ${aiLines} AI).`
    )
  } catch (e: any) {
    vscode.window.showErrorMessage(`Ghostline: Sync failed — ${e?.message ?? e}`)
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
        owner,
        repo,
        path: 'data/index.json',
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
