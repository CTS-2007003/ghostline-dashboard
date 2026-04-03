#!/usr/bin/env node
/**
 * aggregate.js — run by GitHub Actions on every push to data/*.json
 * Reads all per-developer files, builds summary.json for the dashboard.
 * Trend is NOT pre-built here — the dashboard filters it client-side.
 */

const fs = require('fs')
const path = require('path')

const DATA_DIR = path.join(__dirname, '..', 'data')
const SUMMARY_PATH = path.join(DATA_DIR, 'summary.json')

function readDevFiles() {
  return fs.readdirSync(DATA_DIR)
    .filter(f => f.endsWith('.json') && f !== 'summary.json')
    .map(f => {
      try {
        return JSON.parse(fs.readFileSync(path.join(DATA_DIR, f), 'utf-8'))
      } catch {
        return null
      }
    })
    .filter(Boolean)
}

function aggregate() {
  const devs = readDevFiles()

  const summary = {
    generated_at: new Date().toISOString(),
    // All-time totals (used when no date filter is active)
    total_lines_written: devs.reduce((s, d) => s + (d.total_lines_written || 0), 0),
    total_ai_lines: devs.reduce((s, d) => s + (d.total_ai_lines || 0), 0),
    developer_count: devs.length,
    // Full history included — dashboard builds trend for any date range client-side
    developers: devs
      .map(d => ({
        username: d.username,
        ide: d.ide,
        total_lines_written: d.total_lines_written || 0,
        total_ai_lines: d.total_ai_lines || 0,
        history: d.history || [],
        last_updated: d.last_updated
      }))
      .sort((a, b) => b.total_ai_lines - a.total_ai_lines)
  }

  fs.writeFileSync(SUMMARY_PATH, JSON.stringify(summary, null, 2))
  console.log(`Ghostline: aggregated ${devs.length} developer(s) → summary.json`)
}

aggregate()
