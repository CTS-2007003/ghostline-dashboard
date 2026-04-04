import * as vscode from 'vscode'
import { Octokit } from '@octokit/rest'
import { getSession, resetSession } from './tracker'
import { readAndResetSessionFile } from './workspace'

interface DevData {
  username: string
  display_name: string
  team: string
  ide: 'vscode'
  total_lines_written: number
  total_ai_lines: number
  history: { date: string; total: number; ai: number }[]
  last_updated: string
}

function today(): string {
  return new Date().toISOString().slice(0, 10)
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

export async function flush(context: vscode.ExtensionContext) {
  const session = getSession()

  // Read AI lines from session.json (written by AI assistant) and reset it
  const folders = vscode.workspace.workspaceFolders
  const aiLines = folders?.reduce((sum, f) =>
    sum + readAndResetSessionFile(f.uri.fsPath), 0) ?? 0

  if (session.totalLines === 0) return

  const { repo, username, displayName, team } = config()
  if (!repo || !username) return

  const token = await context.secrets.get('ghostline.githubToken')
  if (!token) return

  const [owner, repoName] = repo.split('/')
  const octokit = new Octokit({ auth: token })
  const path = `data/${username}.json`

  const { content: existing, sha } = await readFile(octokit, owner, repoName, path)

  const current: DevData = existing ?? {
    username,
    display_name: displayName || username,
    team: team || '',
    ide: 'vscode',
    total_lines_written: 0,
    total_ai_lines: 0,
    history: [],
    last_updated: ''
  }

  if (displayName) current.display_name = displayName
  if (team) current.team = team

  current.total_lines_written += session.totalLines
  current.total_ai_lines += Math.min(aiLines, session.totalLines) // AI can't exceed total
  current.last_updated = new Date().toISOString()

  const todayStr = today()
  const historyEntry = current.history.find(h => h.date === todayStr)
  if (historyEntry) {
    historyEntry.total += session.totalLines
    historyEntry.ai += Math.min(aiLines, session.totalLines)
  } else {
    current.history.push({
      date: todayStr,
      total: session.totalLines,
      ai: Math.min(aiLines, session.totalLines)
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
  resetSession()
}

async function ensureInIndex(octokit: Octokit, owner: string, repo: string, username: string) {
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
}
