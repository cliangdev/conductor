import { Config } from '../config.js'
import { apiGet, apiPost } from '../api.js'

/**
 * The marketing verification ledger (`/marketing/verification`) and the direct platform reads behind it
 * (`/marketing/verification/reads`). One compact object per metric: Conductor's value, the product backend's
 * value over the days both have, the latest direct read, the verdict and a one-sentence reason. Nulls are dropped
 * and an unknown value stays absent (never zero), per `docs/mcp-tool-guidelines.md`.
 */

export const VERIFICATION_CHANNELS = ['WEB', 'SEARCH', 'SOCIAL', 'ADS', 'STORE', 'PRODUCT'] as const

const ROLLING_WINDOWS = ['7d', '28d', '90d']
const FIXED_WINDOW = /^\d{4}-\d{2}-\d{2}\.\.\d{4}-\d{2}-\d{2}$/
const DAY = /^\d{4}-\d{2}-\d{2}$/

interface BackendDto {
  value: number
  conductorValue: number
  daysCompared: number
  source: string
  matches: boolean
}

interface PlatformReadDto {
  value: number
  from: string
  to: string
  sourceName: string
  conductorValueOverReadDays?: number | null
  matches?: boolean | null
}

interface RowDto {
  channel: string
  metricRef: string
  windowFrom?: string | null
  windowTo?: string | null
  conductorValue?: number | null
  backend?: BackendDto | null
  platformRead?: PlatformReadDto | null
  verdict: string
  mismatch: boolean
  readStale: boolean
  trustNote: string
}

interface LedgerDto {
  window: { from: string; to?: string | null }
  rows: RowDto[]
  summary: Record<string, number>
}

/** Rounds to 4 decimals, or to 2 once the magnitude is 100 or more (money and counts need no more). */
function round(n: number): number {
  if (!Number.isFinite(n)) return n
  const f = 10 ** (Math.abs(n) >= 100 ? 2 : 4)
  return Math.round(n * f) / f
}

function present<T>(value: T | null | undefined): value is T {
  return value !== null && value !== undefined
}

function compactRow(r: RowDto, ledgerWindow?: { from: string; to?: string | null }): Record<string, unknown> {
  const out: Record<string, unknown> = { metric: r.metricRef }
  if (present(r.conductorValue)) out['value'] = round(r.conductorValue)
  out['verdict'] = r.verdict
  if (r.mismatch) out['mismatch'] = true
  // a rolling window ends where each channel's data does, so a row can cover other days than the ledger's window
  if (ledgerWindow && r.windowFrom && r.windowTo && (r.windowFrom !== ledgerWindow.from || r.windowTo !== ledgerWindow.to)) {
    out['over'] = `${r.windowFrom}..${r.windowTo}`
  }
  if (r.backend) {
    out['backend'] = {
      value: round(r.backend.value),
      conductorValue: round(r.backend.conductorValue),
      days: r.backend.daysCompared,
      source: r.backend.source,
      matches: r.backend.matches,
    }
  }
  if (r.platformRead) {
    const read: Record<string, unknown> = {
      value: round(r.platformRead.value),
      from: r.platformRead.from,
      to: r.platformRead.to,
      source: r.platformRead.sourceName,
    }
    if (present(r.platformRead.conductorValueOverReadDays)) {
      read['conductorValue'] = round(r.platformRead.conductorValueOverReadDays)
    }
    if (present(r.platformRead.matches)) read['matches'] = r.platformRead.matches
    out['read'] = read
  }
  if (r.readStale) out['stale'] = true
  out['note'] = r.trustNote
  return out
}

function requireChannel(raw: string | undefined): string {
  const channel = raw?.trim().toUpperCase() ?? ''
  if (!(VERIFICATION_CHANNELS as readonly string[]).includes(channel)) {
    throw new Error(`Unknown channel "${raw}": use one of ${VERIFICATION_CHANNELS.join(', ')}`)
  }
  return channel
}

/** A row is a problem when no second source has confirmed it, a second source disagrees, or its read has gone stale. */
function isProblem(r: RowDto): boolean {
  return r.verdict === 'UNCHECKED' || r.mismatch || r.readStale
}

export async function getVerificationLedger(
  config: Config,
  params: { window?: string; channel?: string; onlyProblems?: boolean }
): Promise<Record<string, unknown>> {
  const window = params.window ?? '28d'
  if (!ROLLING_WINDOWS.includes(window) && !FIXED_WINDOW.test(window)) {
    throw new Error(
      `Unknown window "${window}": use "7d", "28d", "90d" or a range of days YYYY-MM-DD..YYYY-MM-DD`
    )
  }
  const channel = params.channel === undefined ? undefined : requireChannel(params.channel)
  const ledger = await apiGet<LedgerDto>(
    `/api/v2/projects/${config.projectId}/marketing/verification?window=${window}`,
    config
  )
  let rows = ledger.rows
  if (channel) rows = rows.filter((r) => r.channel === channel)
  if (params.onlyProblems) rows = rows.filter(isProblem)
  const out: Record<string, unknown> = {
    window: { from: ledger.window.from, to: ledger.window.to ?? null },
    summary: ledger.summary,
    rows: rows.map((r) => compactRow(r, ledger.window)),
  }
  if (channel || params.onlyProblems) {
    out['shown'] = rows.length
    out['of'] = ledger.rows.length
  }
  return out
}

export interface RecordPlatformReadParams {
  channel: string
  metric: string
  from: string
  to: string
  value: number
  sourceName: string
  note?: string
}

export async function recordPlatformRead(
  config: Config,
  p: RecordPlatformReadParams
): Promise<Record<string, unknown>> {
  const channel = requireChannel(p.channel)
  if (!p.metric?.trim()) throw new Error('metric is required: the metric key within the channel, e.g. installs')
  if (!DAY.test(p.from ?? '')) throw new Error('from must be the first day the platform showed, YYYY-MM-DD')
  if (!DAY.test(p.to ?? '')) throw new Error('to must be the last day the platform showed, YYYY-MM-DD')
  if (p.from > p.to) throw new Error(`from (${p.from}) is after to (${p.to}): give the first day, then the last`)
  if (typeof p.value !== 'number' || !Number.isFinite(p.value) || p.value < 0) {
    throw new Error('value must be the platform\'s figure, a number of zero or more')
  }
  if (!p.sourceName?.trim()) throw new Error('sourceName is required: say where the figure was read, e.g. Apple Ads console')
  const note = p.note?.trim()
  const body: Record<string, unknown> = {
    channel,
    metric: p.metric.trim(),
    from: p.from,
    to: p.to,
    value: p.value,
    sourceName: p.sourceName.trim(),
  }
  if (note) body['note'] = note
  const saved = await apiPost<RowDto>(`/api/v2/projects/${config.projectId}/marketing/verification/reads`, body, config)
  return { recorded: true, row: compactRow(saved) }
}
