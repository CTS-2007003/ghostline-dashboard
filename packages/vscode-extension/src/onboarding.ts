import * as vscode from 'vscode'
import * as cp from 'child_process'
import { Octokit } from '@octokit/rest'

/**
 * Runs the first-time setup wizard.
 * Steps: repo → token → username (auto-detected) → validate → done.
 * Safe to call on every activation — exits immediately if already configured.
 */
export async function runOnboardingIfNeeded(context: vscode.ExtensionContext) {
  const cfg = vscode.workspace.getConfiguration('ghostline')
  const alreadyConfigured =
    cfg.get<string>('githubRepo') &&
    cfg.get<string>('githubUsername') &&
    (await context.secrets.get('ghostline.githubToken'))

  if (alreadyConfigured) return

  const start = await vscode.window.showInformationMessage(
    'Welcome to Ghostline! Only line counts (numbers) are ever synced — no code, no filenames, nothing else.',
    { modal: false },
    'Get Started',
    'Later'
  )
  if (start !== 'Get Started') return

  await wizard(context)
}

async function wizard(context: vscode.ExtensionContext) {
  // ── Step 1: Dashboard repo ───────────────────────────────────────────────
  const repo = await vscode.window.showInputBox({
    title: 'Ghostline Setup (1/3) — Dashboard Repo',
    prompt: 'Enter the GitHub repo that will store your team dashboard',
    placeHolder: 'myorg/ghostline-dashboard',
    ignoreFocusOut: true,
    validateInput: v => {
      if (!v) return 'Required'
      if (!/^[\w.-]+\/[\w.-]+$/.test(v)) return 'Must be in owner/repo format'
      return null
    }
  })
  if (!repo) return

  // ── Step 2: GitHub PAT ───────────────────────────────────────────────────
  const [owner] = repo.split('/')
  const patUrl = `https://github.com/settings/personal-access-tokens/new`

  const tokenPrompt = await vscode.window.showInformationMessage(
    `You need a GitHub PAT with Contents: Read & Write access on "${repo}".`,
    { modal: true },
    'Open GitHub to Create Token',
    'I Already Have One'
  )
  if (!tokenPrompt) return
  if (tokenPrompt === 'Open GitHub to Create Token') {
    vscode.env.openExternal(vscode.Uri.parse(patUrl))
  }

  const token = await vscode.window.showInputBox({
    title: 'Ghostline Setup (2/3) — GitHub Token',
    prompt: `Paste your GitHub Personal Access Token (repo: ${repo})`,
    placeHolder: 'ghp_... or github_pat_...',
    password: true,
    ignoreFocusOut: true,
    validateInput: v => {
      if (!v) return 'Required'
      if (!v.startsWith('ghp_') && !v.startsWith('github_pat_')) {
        return 'Must be a valid GitHub PAT (starts with ghp_ or github_pat_)'
      }
      return null
    }
  })
  if (!token) return

  // ── Step 3: Username (auto-detect from git config) ───────────────────────
  const detectedUsername = await detectGitUsername()
  const username = await vscode.window.showInputBox({
    title: 'Ghostline Setup (3/4) — Your GitHub Username',
    prompt: 'Your GitHub username (used to name your data file)',
    value: detectedUsername ?? '',
    placeHolder: 'your-github-username',
    ignoreFocusOut: true,
    validateInput: v => (v ? null : 'Required')
  })
  if (!username) return

  // ── Step 4: Display name ─────────────────────────────────────────────────
  const displayName = await vscode.window.showInputBox({
    title: 'Ghostline Setup (4/5) — Your Display Name',
    prompt: 'Name shown on the team dashboard (your real name or nickname)',
    value: detectedUsername ?? '',
    placeHolder: 'e.g. Alex or Alex Kumar',
    ignoreFocusOut: true,
    validateInput: v => (v ? null : 'Required')
  })
  if (!displayName) return

  // ── Step 5: Team ─────────────────────────────────────────────────────────
  const team = await vscode.window.showInputBox({
    title: 'Ghostline Setup (5/5) — Your Team',
    prompt: 'Team name shown on the dashboard (e.g. Frontend, Backend, Mobile)',
    placeHolder: 'e.g. Frontend',
    ignoreFocusOut: true,
    validateInput: v => (v ? null : 'Required')
  })
  if (!team) return

  // ── Validate ─────────────────────────────────────────────────────────────
  await vscode.window.withProgress(
    { location: vscode.ProgressLocation.Notification, title: 'Ghostline: Validating...', cancellable: false },
    async () => {
      const error = await validateAccess(token, repo)
      if (error) {
        vscode.window.showErrorMessage(`Ghostline: ${error}`)
        return
      }

      // Save everything
      await context.secrets.store('ghostline.githubToken', token)
      await vscode.workspace.getConfiguration('ghostline').update('githubRepo', repo, true)
      await vscode.workspace.getConfiguration('ghostline').update('githubUsername', username, true)
      await vscode.workspace.getConfiguration('ghostline').update('displayName', displayName, true)
      await vscode.workspace.getConfiguration('ghostline').update('team', team, true)

      const [repoOwner, repoName] = repo.split('/')
      const dashboardUrl = `https://${repoOwner}.github.io/${repoName}`
      const action = await vscode.window.showInformationMessage(
        `Ghostline is ready! Your lines will sync to the team dashboard.`,
        'Open Dashboard'
      )
      if (action === 'Open Dashboard') {
        vscode.env.openExternal(vscode.Uri.parse(dashboardUrl))
      }
    }
  )
}

async function validateAccess(token: string, repo: string): Promise<string | null> {
  try {
    const [owner, repoName] = repo.split('/')
    const octokit = new Octokit({ auth: token })
    await octokit.repos.get({ owner, repo: repoName })
    return null
  } catch (e: any) {
    if (e.status === 401) return 'Token is invalid or expired.'
    if (e.status === 404) return `Repo "${repo}" not found. Check the name and token permissions.`
    return `Could not reach GitHub: ${e.message}`
  }
}

function detectGitUsername(): Promise<string | undefined> {
  return new Promise(resolve => {
    cp.exec('git config --global user.name', (err, stdout) => {
      resolve(err ? undefined : stdout.trim() || undefined)
    })
  })
}
