/**
 * Local ledger of estimated video spend, keyed by local date, so the daily cap holds across sessions.
 * `~/.conductor/video-spend.json`: { "2026-11-04": 1.2 }. Only today and the last few days are kept.
 */
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

export const SPEND_PATH = path.join(os.homedir(), '.conductor', 'video-spend.json')
const KEEP_DAYS = 7

export interface SpendOptions {
  path?: string
  now?: Date
}

export function localDate(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

function read(file: string): Record<string, number> {
  try {
    const parsed = JSON.parse(fs.readFileSync(file, 'utf8')) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    return Object.fromEntries(Object.entries(parsed).filter(([, v]) => typeof v === 'number' && Number.isFinite(v))) as Record<string, number>
  } catch {
    return {}
  }
}

export function spentToday(opts: SpendOptions = {}): number {
  return read(opts.path ?? SPEND_PATH)[localDate(opts.now ?? new Date())] ?? 0
}

export function record(amountUsd: number, opts: SpendOptions = {}): number {
  const file = opts.path ?? SPEND_PATH
  const now = opts.now ?? new Date()
  const today = localDate(now)
  const cutoff = localDate(new Date(now.getFullYear(), now.getMonth(), now.getDate() - KEEP_DAYS))
  const ledger = Object.fromEntries(Object.entries(read(file)).filter(([day]) => day >= cutoff))
  ledger[today] = Math.round(((ledger[today] ?? 0) + amountUsd) * 10000) / 10000
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true })
    fs.writeFileSync(file, JSON.stringify(ledger, null, 2) + '\n', { mode: 0o600 })
  } catch {
    /* a ledger that cannot be written must not lose the clip */
  }
  return ledger[today]
}
