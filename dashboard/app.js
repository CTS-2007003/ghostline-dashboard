const GITHUB_RAW = 'https://raw.githubusercontent.com/cts-2007003/ghostline-dashboard/master'
const INDEX_URL = `${GITHUB_RAW}/data/index.json`
const REFRESH_INTERVAL = 60_000

let trendChart = null
let splitChart = null
let summaryData = null

// Active filter state
let activeFilter = { type: 'last30', from: null, to: null }

// ── Date helpers ─────────────────────────────────────────────────────────────

function toDateStr(date) {
  return date.toISOString().slice(0, 10)
}

function getDateRange(filter) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)

  switch (filter.type) {
    case 'last7': {
      const from = new Date(today)
      from.setDate(from.getDate() - 6)
      return { from: toDateStr(from), to: toDateStr(today) }
    }
    case 'last30': {
      const from = new Date(today)
      from.setDate(from.getDate() - 29)
      return { from: toDateStr(from), to: toDateStr(today) }
    }
    case 'thisMonth': {
      const from = new Date(today.getFullYear(), today.getMonth(), 1)
      return { from: toDateStr(from), to: toDateStr(today) }
    }
    case 'lastMonth': {
      const from = new Date(today.getFullYear(), today.getMonth() - 1, 1)
      const to = new Date(today.getFullYear(), today.getMonth(), 0)
      return { from: toDateStr(from), to: toDateStr(to) }
    }
    case 'custom':
      return { from: filter.from, to: filter.to }
    case 'allTime':
    default:
      return null
  }
}

function filterLabel(filter) {
  const range = getDateRange(filter)
  if (!range) return 'All Time'
  if (filter.type === 'thisMonth') return 'This Month'
  if (filter.type === 'lastMonth') return 'Last Month'
  if (filter.type === 'last7') return 'Last 7 Days'
  if (filter.type === 'last30') return 'Last 30 Days'
  return `${range.from} → ${range.to}`
}

// ── Data helpers ──────────────────────────────────────────────────────────────

function computeTotals(devs, range) {
  // All-time: use pre-computed totals from summary
  if (!range) {
    return {
      total: summaryData.total_lines_written,
      ai: summaryData.total_ai_lines
    }
  }
  // Filtered: sum up history entries within range
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

function buildTrend(devs, range) {
  if (!range) return []

  // Enumerate every date in range
  const dates = []
  const cursor = new Date(range.from)
  const end = new Date(range.to)
  while (cursor <= end) {
    dates.push(toDateStr(cursor))
    cursor.setDate(cursor.getDate() + 1)
  }

  return dates.map(date => {
    let total = 0, ai = 0
    for (const dev of devs) {
      const entry = (dev.history || []).find(h => h.date === date)
      if (entry) { total += entry.total; ai += entry.ai }
    }
    return { date, total, ai }
  })
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
  document.getElementById('lastUpdated').textContent = timeAgo(summaryData.generated_at)
}

function renderTeamTable(devs, range) {
  const tbody = document.getElementById('teamBody')
  if (!devs.length) {
    tbody.innerHTML = '<tr><td colspan="7" class="loading">No data yet.</td></tr>'
    return
  }

  const rows = devs
    .map(dev => ({ dev, ...computeDevTotals(dev, range) }))
    .sort((a, b) => b.ai - a.ai)

  tbody.innerHTML = rows.map(({ dev, total, ai }) => {
    const human = total - ai
    const pct = total > 0 ? Math.round((ai / total) * 100) : 0
    return `
      <tr>
        <td><strong>${dev.username}</strong></td>
        <td><span class="ide-badge">${dev.ide}</span></td>
        <td>${fmt(total)}</td>
        <td style="color:var(--ai-light)">${fmt(ai)}</td>
        <td style="color:var(--human-light)">${fmt(human)}</td>
        <td>
          <div class="ai-pct-bar">
            <div class="bar-track"><div class="bar-fill" style="width:${pct}%"></div></div>
            ${pct}%
          </div>
        </td>
        <td style="color:var(--muted)">${timeAgo(dev.last_updated)}</td>
      </tr>`
  }).join('')
}

function renderTrendChart(trend) {
  const ctx = document.getElementById('trendChart').getContext('2d')
  if (trendChart) trendChart.destroy()

  if (!trend.length) {
    trendChart = null
    ctx.clearRect(0, 0, ctx.canvas.width, ctx.canvas.height)
    ctx.fillStyle = '#8b949e'
    ctx.textAlign = 'center'
    ctx.fillText('No data for selected range', ctx.canvas.width / 2, 80)
    return
  }

  // For wide ranges (> 60 days), group by week to keep chart readable
  const grouped = trend.length > 60 ? groupByWeek(trend) : trend
  const labels = grouped.map(d => d.label || d.date.slice(5))

  trendChart = new Chart(ctx, {
    type: 'bar',
    data: {
      labels,
      datasets: [
        { label: 'AI', data: grouped.map(d => d.ai), backgroundColor: 'rgba(124,58,237,0.8)', borderRadius: 3 },
        { label: 'Human', data: grouped.map(d => d.total - d.ai), backgroundColor: 'rgba(8,145,178,0.8)', borderRadius: 3 }
      ]
    },
    options: {
      responsive: true,
      plugins: { legend: { labels: { color: '#8b949e' } } },
      scales: {
        x: { stacked: true, ticks: { color: '#8b949e', maxTicksLimit: 20 }, grid: { color: '#30363d' } },
        y: { stacked: true, ticks: { color: '#8b949e' }, grid: { color: '#30363d' } }
      }
    }
  })
}

function groupByWeek(trend) {
  const weeks = {}
  for (const d of trend) {
    const date = new Date(d.date)
    const weekStart = new Date(date)
    weekStart.setDate(date.getDate() - date.getDay())
    const key = toDateStr(weekStart)
    if (!weeks[key]) weeks[key] = { label: `w/${key.slice(5)}`, total: 0, ai: 0 }
    weeks[key].total += d.total
    weeks[key].ai += d.ai
  }
  return Object.values(weeks)
}

function renderSplitChart(totals) {
  const ctx = document.getElementById('splitChart').getContext('2d')
  if (splitChart) splitChart.destroy()

  splitChart = new Chart(ctx, {
    type: 'doughnut',
    data: {
      labels: ['AI', 'Human'],
      datasets: [{
        data: [totals.ai, totals.total - totals.ai],
        backgroundColor: ['rgba(124,58,237,0.85)', 'rgba(8,145,178,0.85)'],
        borderColor: '#161b22',
        borderWidth: 3
      }]
    },
    options: {
      responsive: true,
      plugins: { legend: { labels: { color: '#8b949e' } } },
      cutout: '65%'
    }
  })
}

// ── Apply filter & re-render everything ───────────────────────────────────────

function applyFilter() {
  if (!summaryData) return

  const range = getDateRange(activeFilter)
  const totals = computeTotals(summaryData.developers, range)
  const trend = range ? buildTrend(summaryData.developers, range) : []

  document.getElementById('trendLabel').textContent = filterLabel(activeFilter)

  renderCards(totals)
  renderTeamTable(summaryData.developers, range)
  renderTrendChart(trend)
  renderSplitChart(totals)
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
  document.getElementById('customFrom').value = toDateStr(firstOfMonth)
  document.getElementById('customTo').value = toDateStr(today)

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

  // 1. Fetch the index to get all usernames
  const idxRes = await fetch(INDEX_URL + bust)
  if (!idxRes.ok) throw new Error('No index.json yet — no developers have synced.')
  const usernames = await idxRes.json()

  // 2. Fetch all developer files in parallel
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
  try {
    summaryData = await fetchAll()
    applyFilter()
  } catch (e) {
    console.warn('Ghostline: failed to refresh —', e.message)
    document.getElementById('lastUpdated').textContent = 'unavailable'
  }
}

initFilterBar()
refresh()
setInterval(refresh, REFRESH_INTERVAL)
