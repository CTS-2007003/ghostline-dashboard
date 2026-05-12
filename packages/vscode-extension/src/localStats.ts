import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

export interface ExtLines { dev: number; test: number }

export interface LocalStats {
  last_updated: string
  by_extension: Record<string, ExtLines>
}

export function statsPath(): string {
  return path.join(os.homedir(), '.ghostline', 'stats.json')
}

export function readLocalStats(): LocalStats {
  try {
    const raw = fs.readFileSync(statsPath(), 'utf-8')
    return JSON.parse(raw) as LocalStats
  } catch {
    return { last_updated: '', by_extension: {} }
  }
}

/** Atomically merge a per-extension delta into the cumulative local stats file. */
export function writeLocalStatsDelta(delta: Map<string, ExtLines>): void {
  if (delta.size === 0) return

  const dir = path.join(os.homedir(), '.ghostline')
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })

  const existing = readLocalStats()

  for (const [ext, lines] of delta) {
    const cur = existing.by_extension[ext] ?? { dev: 0, test: 0 }
    existing.by_extension[ext] = {
      dev: cur.dev + lines.dev,
      test: cur.test + lines.test
    }
  }

  existing.last_updated = nowIso()

  const file = statsPath()
  const tmp = file + '.tmp'
  fs.writeFileSync(tmp, JSON.stringify(existing, null, 2), 'utf-8')
  fs.renameSync(tmp, file)
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
