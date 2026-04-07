const GITHUB_RAW = 'https://raw.githubusercontent.com/cts-2007003/ghostline-dashboard/master'
const INDEX_URL = `${GITHUB_RAW}/data/index.json`
const REFRESH_INTERVAL = 60_000

let summaryData = null
let activeTeam = ''  // '' = all teams
let sortState = { col: 'total', asc: false }  // default: total desc

// Active filter state
let activeFilter = { type: 'last30', from: null, to: null }

// ── Date helpers ──────────────────────────────────────────────────────────────
// Always use local date strings to avoid UTC offset shifting "today" to yesterday

function toLocalDateStr(date) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

function getDateRange(filter) {
  const today = new Date()

  switch (filter.type) {
    case 'last7': {
      const from = new Date(today)
      from.setDate(from.getDate() - 6)
      return { from: toLocalDateStr(from), to: toLocalDateStr(today) }
    }
    case 'last30': {
      const from = new Date(today)
      from.setDate(from.getDate() - 29)
      return { from: toLocalDateStr(from), to: toLocalDateStr(today) }
    }
    case 'thisMonth': {
      const from = new Date(today.getFullYear(), today.getMonth(), 1)
      return { from: toLocalDateStr(from), to: toLocalDateStr(today) }
    }
    case 'lastMonth': {
      const from = new Date(today.getFullYear(), today.getMonth() - 1, 1)
      const to = new Date(today.getFullYear(), today.getMonth(), 0)
      return { from: toLocalDateStr(from), to: toLocalDateStr(to) }
    }
    case 'custom':
      return { from: filter.from, to: filter.to }
    case 'allTime':
    default:
      return null
  }
}

// ── Data helpers ──────────────────────────────────────────────────────────────

function sanitize({ total, ai }) {
  const t = Math.max(0, total || 0)
  const a = Math.min(Math.max(0, ai || 0), t)  // ai never negative, never > total
  return { total: t, ai: a }
}

function computeTotals(devs, range) {
  if (!range) {
    // Sum from filtered devs so team filter applies to All Time too
    return sanitize({
      total: devs.reduce((s, d) => s + (d.total_lines_written || 0), 0),
      ai: devs.reduce((s, d) => s + (d.total_ai_lines || 0), 0)
    })
  }
  let total = 0, ai = 0
  for (const dev of devs) {
    for (const entry of (dev.history || [])) {
      if (entry.date >= range.from && entry.date <= range.to) {
        total += entry.total || 0
        ai += entry.ai || 0  // guard against missing ai field in old data
      }
    }
  }
  return sanitize({ total, ai })
}

function computeDevTotals(dev, range) {
  if (!range) {
    return sanitize({ total: dev.total_lines_written, ai: dev.total_ai_lines })
  }
  let total = 0, ai = 0
  for (const entry of (dev.history || [])) {
    if (entry.date >= range.from && entry.date <= range.to) {
      total += entry.total || 0
      ai += entry.ai || 0
    }
  }
  return sanitize({ total, ai })
}

// ── Render ────────────────────────────────────────────────────────────────────

function fmt(n) { return n.toLocaleString() }

function esc(str) {
  return String(str ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;')
    .replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function timeAgo(isoString) {
  if (!isoString) return '—'
  const ts = new Date(isoString)
  if (isNaN(ts.getTime())) return '—'  // guard against corrupt date strings
  const diff = Math.floor((Date.now() - ts) / 1000)
  if (diff < 0) return 'just now'   // clock skew guard
  if (diff < 60) return 'just now'
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`
  return `${Math.floor(diff / 86400)}d ago`
}

function renderCards(totals, devs) {
  const human = totals.total - totals.ai
  const pct = totals.total > 0 ? Math.round((totals.ai / totals.total) * 100) : 0

  document.getElementById('totalLines').textContent = fmt(totals.total)
  document.getElementById('totalAi').textContent = fmt(totals.ai)
  document.getElementById('totalHuman').textContent = fmt(human)
  document.getElementById('aiRatio').textContent = pct + '%'

  const lastSync = devs.map(d => d.last_updated).filter(Boolean).sort().at(-1)
  document.getElementById('lastUpdated').textContent = lastSync ? timeAgo(lastSync) : 'no syncs yet'
}

function renderTeamTable(devs, range) {
  const tbody = document.getElementById('teamBody')
  if (!devs.length) {
    tbody.innerHTML = '<tr><td colspan="5" class="loading">No data yet.</td></tr>'
    return
  }

  const rows = devs.map(dev => {
    const { total, ai } = computeDevTotals(dev, range)
    const human = total - ai
    const pct = total > 0 ? Math.min(100, Math.round((ai / total) * 100)) : 0
    return { dev, total, ai, human, pct }
  })

  const { col, asc } = sortState
  rows.sort((a, b) => {
    const diff = a[col] - b[col]
    return asc ? diff : -diff
  })

  // Update header indicators
  document.querySelectorAll('th.sortable').forEach(th => {
    const active = th.dataset.col === col
    th.classList.toggle('sort-asc', active && asc)
    th.classList.toggle('sort-desc', active && !asc)
  })

  tbody.innerHTML = rows.map(({ dev, total, ai, human, pct }) => `
    <tr>
      <td><strong>${esc(dev.display_name || dev.username)}</strong></td>
      <td>${fmt(total)}</td>
      <td style="color:var(--ai-light)">${fmt(ai)}</td>
      <td style="color:var(--human-light)">${fmt(human)}</td>
      <td>
        <div class="ai-pct-bar">
          <div class="bar-track"><div class="bar-fill" style="width:${pct}%"></div></div>
          ${pct}%
        </div>
      </td>
    </tr>`).join('')
}

// ── Apply filter & re-render ──────────────────────────────────────────────────

function filteredDevs() {
  if (!activeTeam) return summaryData.developers
  return summaryData.developers.filter(d => (d.team || '') === activeTeam)
}

function applyFilter() {
  if (!summaryData) return

  const devs = filteredDevs()
  const range = getDateRange(activeFilter)
  const totals = computeTotals(devs, range)

  renderCards(totals, devs)
  renderTeamTable(devs, range)
}

function populateTeamFilter(devs) {
  const select = document.getElementById('teamFilter')
  const current = select.value

  const teams = [...new Set(devs.map(d => d.team || '').filter(Boolean))].sort()
  select.innerHTML = '<option value="">All Teams</option>' +
    teams.map(t => `<option value="${esc(t)}">${esc(t)}</option>`).join('')

  // Restore previous selection if still valid, otherwise reset to avoid stale filter
  if (teams.includes(current)) {
    select.value = current
  } else {
    activeTeam = ''
  }
}

// ── Filter bar wiring ─────────────────────────────────────────────────────────

function initFilterBar() {
  document.querySelectorAll('.filter-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'))
      btn.classList.add('active')

      const type = btn.dataset.filter
      document.getElementById('customRange').style.display = type === 'custom' ? 'flex' : 'none'

      if (type !== 'custom') {
        activeFilter = { type, from: null, to: null }
        applyFilter()
      }
    })
  })

  // Default custom date range to current month
  const today = new Date()
  const firstOfMonth = new Date(today.getFullYear(), today.getMonth(), 1)
  document.getElementById('customFrom').value = toLocalDateStr(firstOfMonth)
  document.getElementById('customTo').value = toLocalDateStr(today)

  document.getElementById('applyCustom').addEventListener('click', () => {
    const from = document.getElementById('customFrom').value
    const to = document.getElementById('customTo').value
    if (!from || !to || from > to) {
      alert('Please select a valid date range (From must be before To).')
      return
    }
    activeFilter = { type: 'custom', from, to }
    applyFilter()
  })
}

// ── Fetch & main loop ─────────────────────────────────────────────────────────

async function fetchAll() {
  // cache: 'reload' sends Cache-Control: no-cache to the CDN, forcing a fresh copy
  const opts = { cache: 'reload' }

  const idxRes = await fetch(INDEX_URL, opts)
  if (!idxRes.ok) throw new Error('No index.json yet — no developers have synced.')
  const usernames = await idxRes.json()

  const devFiles = await Promise.all(
    usernames.map(u =>
      fetch(`${GITHUB_RAW}/data/${u}.json`, opts)
        .then(r => r.ok ? r.json() : null)
        .catch(() => null)
    )
  )
  const developers = devFiles.filter(Boolean)

  return {
    generated_at: new Date().toISOString(),
    total_lines_written: developers.reduce((s, d) => s + (d.total_lines_written || 0), 0),
    total_ai_lines: developers.reduce((s, d) => s + (d.total_ai_lines || 0), 0),
    developers
  }
}

let refreshing = false

async function refresh() {
  if (refreshing) return
  refreshing = true

  const pulse = document.getElementById('pulse')
  const btn = document.getElementById('refreshBtn')
  const lastUpdatedEl = document.getElementById('lastUpdated')

  pulse.style.background = 'var(--muted)'
  if (btn) { btn.disabled = true; btn.textContent = '…' }
  lastUpdatedEl.textContent = 'Refreshing…'

  try {
    const fresh = await fetchAll()
    summaryData = fresh
    populateTeamFilter(summaryData.developers)
    applyFilter()  // renderCards updates lastUpdated with real value
    pulse.style.background = ''
  } catch (e) {
    console.warn('Ghostline:', e.message)
    pulse.style.background = '#f85149'
    const msg = !summaryData
      ? (e.message.includes('index.json') ? 'no data yet — install the extension and sync' : `fetch error: ${e.message}`)
      : 'refresh failed — showing last known data'
    lastUpdatedEl.textContent = msg
  } finally {
    refreshing = false
    if (btn) { btn.disabled = false; btn.textContent = '↻' }
  }
}

// Show user's local timezone so date ranges are unambiguous
document.getElementById('tzBadge').textContent =
  Intl.DateTimeFormat().resolvedOptions().timeZone

document.querySelectorAll('th.sortable').forEach(th => {
  th.addEventListener('click', () => {
    const col = th.dataset.col
    sortState = col === sortState.col
      ? { col, asc: !sortState.asc }   // same column → flip direction
      : { col, asc: false }             // new column → start descending
    applyFilter()
  })
})

initFilterBar()
document.getElementById('teamFilter').addEventListener('change', e => {
  activeTeam = e.target.value
  applyFilter()
})
document.getElementById('refreshBtn').addEventListener('click', refresh)
document.getElementById('clearCacheBtn').addEventListener('click', async () => {
  // Clear any service worker caches
  if ('caches' in window) {
    const keys = await caches.keys()
    await Promise.all(keys.map(k => caches.delete(k)))
  }
  // Hard reload — forces GitHub Pages to serve fresh HTML, JS, CSS
  window.location.replace(window.location.href.split('?')[0] + '?v=' + Date.now())
})
refresh()

// Pause fetching when tab is hidden, resume (and refresh immediately) when visible again
let timer = setInterval(refresh, REFRESH_INTERVAL)
document.addEventListener('visibilitychange', () => {
  if (document.hidden) {
    clearInterval(timer)
  } else {
    refresh()
    timer = setInterval(refresh, REFRESH_INTERVAL)
  }
})
