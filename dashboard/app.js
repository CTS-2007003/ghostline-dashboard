const GITHUB_RAW = 'https://raw.githubusercontent.com/cts-2007003/ghostline-dashboard/master'
const INDEX_URL = `${GITHUB_RAW}/data/index.json`
const REFRESH_INTERVAL = 60_000

let summaryData = null

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

function computeTotals(devs, range) {
  if (!range) {
    return {
      total: summaryData.total_lines_written,
      ai: summaryData.total_ai_lines
    }
  }
  let total = 0, ai = 0
  for (const dev of devs) {
    for (const entry of (dev.history || [])) {
      if (entry.date >= range.from && entry.date <= range.to) {
        total += entry.total
        ai += entry.ai
      }
    }
  }
  return { total, ai }
}

function computeDevTotals(dev, range) {
  if (!range) {
    return { total: dev.total_lines_written, ai: dev.total_ai_lines }
  }
  let total = 0, ai = 0
  for (const entry of (dev.history || [])) {
    if (entry.date >= range.from && entry.date <= range.to) {
      total += entry.total
      ai += entry.ai
    }
  }
  return { total, ai }
}

// ── Render ────────────────────────────────────────────────────────────────────

function fmt(n) { return n.toLocaleString() }

function timeAgo(isoString) {
  if (!isoString) return '—'
  const diff = Math.floor((Date.now() - new Date(isoString)) / 1000)
  if (diff < 60) return 'just now'
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`
  return `${Math.floor(diff / 86400)}d ago`
}

function renderCards(totals) {
  const human = totals.total - totals.ai
  const pct = totals.total > 0 ? Math.round((totals.ai / totals.total) * 100) : 0

  document.getElementById('totalLines').textContent = fmt(totals.total)
  document.getElementById('totalAi').textContent = fmt(totals.ai)
  document.getElementById('totalHuman').textContent = fmt(human)
  document.getElementById('aiRatio').textContent = pct + '%'

  // Show when a developer last actually synced, not when the dashboard fetched
  const lastSync = summaryData.developers
    .map(d => d.last_updated)
    .filter(Boolean)
    .sort()
    .at(-1)
  document.getElementById('lastUpdated').textContent = lastSync ? timeAgo(lastSync) : 'no syncs yet'
}

function renderTeamTable(devs, range) {
  const tbody = document.getElementById('teamBody')
  if (!devs.length) {
    tbody.innerHTML = '<tr><td colspan="5" class="loading">No data yet.</td></tr>'
    return
  }

  const rows = devs
    .map(dev => ({ dev, ...computeDevTotals(dev, range) }))
    .sort((a, b) => b.total - a.total)

  tbody.innerHTML = rows.map(({ dev, total, ai }) => {
    const human = total - ai
    const pct = total > 0 ? Math.round((ai / total) * 100) : 0
    return `
      <tr>
        <td><strong>${dev.display_name || dev.username}</strong></td>
        <td>${fmt(total)}</td>
        <td style="color:var(--ai-light)">${fmt(ai)}</td>
        <td style="color:var(--human-light)">${fmt(human)}</td>
        <td>
          <div class="ai-pct-bar">
            <div class="bar-track"><div class="bar-fill" style="width:${pct}%"></div></div>
            ${pct}%
          </div>
        </td>
      </tr>`
  }).join('')
}

// ── Apply filter & re-render ──────────────────────────────────────────────────

function applyFilter() {
  if (!summaryData) return

  const range = getDateRange(activeFilter)
  const totals = computeTotals(summaryData.developers, range)

  renderCards(totals)
  renderTeamTable(summaryData.developers, range)
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
  const bust = '?t=' + Date.now()

  const idxRes = await fetch(INDEX_URL + bust)
  if (!idxRes.ok) throw new Error('No index.json yet — no developers have synced.')
  const usernames = await idxRes.json()

  const devFiles = await Promise.all(
    usernames.map(u =>
      fetch(`${GITHUB_RAW}/data/${u}.json${bust}`)
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

async function refresh() {
  const pulse = document.getElementById('pulse')
  pulse.style.background = 'var(--muted)'  // grey while loading

  try {
    const fresh = await fetchAll()
    summaryData = fresh  // only swap on success — stale data stays visible during load
    applyFilter()
    pulse.style.background = ''  // back to CSS animation (green)
  } catch (e) {
    console.warn('Ghostline:', e.message)
    pulse.style.background = '#f85149'  // red dot on error
    if (!summaryData) {
      const msg = e.message.includes('index.json')
        ? 'no data yet — install the extension and sync'
        : `fetch error: ${e.message}`
      document.getElementById('lastUpdated').textContent = msg
    }
    // if stale data exists, leave it on screen — only dot turns red
  }
}

// Show user's local timezone so date ranges are unambiguous
document.getElementById('tzBadge').textContent =
  Intl.DateTimeFormat().resolvedOptions().timeZone

initFilterBar()
refresh()
setInterval(refresh, REFRESH_INTERVAL)
