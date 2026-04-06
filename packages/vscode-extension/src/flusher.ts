import * as vscode from 'vscode'
import { Octokit } from '@octokit/rest'
import { getSession, resetSession } from './tracker'
import { readAndResetSessionFile } from './workspace'

interface DevData {
  username: string
  display_name: string
  team: string
  ides: string[]
  total_lines_written: number
  total_ai_lines: number
  history: { date: string; total: number; ai: number }[]
  last_updated: string
}

function today(): string {
  const d = new Date()
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
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

// Prevents two concurrent flushes from double-counting lines
let flushing = false

export async function flush(context: vscode.ExtensionContext) {
  if (flushing) return
  // Set flag synchronously before any await so a concurrent call can't slip through
  flushing = true

  try {
    // Check prerequisites before touching any files — avoids zeroing session.json
    // when there's no token or config, which would permanently lose AI lines
    const { repo, username, displayName, team } = config()
    if (!repo || !username) return

    const token = await context.secrets.get('ghostline.githubToken')
    if (!token) return

    // Read AI lines BEFORE the totalLines check — the AI may have written lines
    // even if the developer typed nothing this interval
    const folders = vscode.workspace.workspaceFolders
    const aiLines = folders?.reduce((sum, f) =>
      sum + readAndResetSessionFile(f.uri.fsPath), 0) ?? 0

    const session = getSession()
    if (session.totalLines === 0 && aiLines === 0) return

    // Snapshot and reset before network calls so lines typed during the flush
    // aren't silently dropped
    const totalSnap = session.totalLines
    resetSession()

    const [owner, repoName] = repo.split('/')
    const octokit = new Octokit({ auth: token })
    const path = `data/${username}.json`

    const { content: existing, sha } = await readFile(octokit, owner, repoName, path)

    const current: DevData = existing ?? {
      username,
      display_name: displayName || username,
      team: team || '',
      ides: ['vscode'],
      total_lines_written: 0,
      total_ai_lines: 0,
      history: [],
      last_updated: ''
    }

    if (displayName) current.display_name = displayName
    if (team) current.team = team
    if (!current.ides) current.ides = []
    if (!current.ides.includes('vscode')) current.ides.push('vscode')

    const aiSnap = Math.min(aiLines, totalSnap)
    current.total_lines_written += totalSnap
    current.total_ai_lines += aiSnap
    // Store with local timezone offset so the raw JSON is human-readable
    const now = new Date()
    const off = -now.getTimezoneOffset()
    const sign = off >= 0 ? '+' : '-'
    const pad = (n: number) => String(Math.abs(n)).padStart(2, '0')
    current.last_updated = now.toISOString().slice(0, 19) +
      `${sign}${pad(Math.floor(off / 60))}:${pad(off % 60)}`

    const todayStr = today()
    const historyEntry = current.history.find(h => h.date === todayStr)
    if (historyEntry) {
      historyEntry.total += totalSnap
      historyEntry.ai += aiSnap
    } else {
      current.history.push({ date: todayStr, total: totalSnap, ai: aiSnap })
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
  } catch {
    // Silent fail — will retry on next flush
  } finally {
    flushing = false
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
      if (attempt === 0 && e?.status === 422) continue // SHA conflict — retry once
      return // give up silently on second failure
    }
  }
}
