import * as vscode from 'vscode'

const TOKEN_KEY = 'ghostline.githubToken'

export async function getToken(context: vscode.ExtensionContext): Promise<string | undefined> {
  return context.secrets.get(TOKEN_KEY)
}

export async function setToken(context: vscode.ExtensionContext): Promise<string | undefined> {
  const token = await vscode.window.showInputBox({
    prompt: 'Enter your GitHub Personal Access Token',
    placeHolder: 'ghp_...',
    password: true,
    ignoreFocusOut: true,
    validateInput: v => (v.startsWith('ghp_') || v.startsWith('github_pat_') ? null : 'Must be a valid GitHub PAT')
  })

  if (!token) return undefined

  await context.secrets.store(TOKEN_KEY, token)
  vscode.window.showInformationMessage('Ghostline: GitHub token saved.')
  return token
}

export async function ensureToken(context: vscode.ExtensionContext): Promise<string | undefined> {
  const existing = await getToken(context)
  if (existing) return existing

  const action = await vscode.window.showInformationMessage(
    'Ghostline needs a GitHub token to sync your AI line counts.',
    'Set Token',
    'Not Now'
  )

  if (action === 'Set Token') return setToken(context)
  return undefined
}
