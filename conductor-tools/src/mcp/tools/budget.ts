import { Config } from '../config.js'
import { apiGet, apiPut, apiPost, apiDelete } from '../api.js'

/**
 * The monthly paid budget (`/marketing/budget`) and the stop rules that guard each paid channel
 * (`/marketing/stop-rules`). Conductor only records and evaluates; the bid or budget change itself is made by a
 * human in the ad platform. The pacing payload carries full daily series per channel, so this compacts it per
 * `docs/mcp-tool-guidelines.md`: absent stays absent, but a null that means something ("no cap", "no installs")
 * is kept explicit.
 */

export const RULE_METRICS = ['cost_per_install', 'spend', 'installs', 'taps', 'tap_through_rate', 'install_rate'] as const
export const RECENT_DAILY_DAYS = 7
export const MAX_WINDOW_DAYS = 90

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
const SCOPE = /^(channel|(campaign|keyword):[A-Za-z0-9._-]{1,100})$/i

type Json = unknown
type Obj = Record<string, Json>

function round(n: number | null | undefined, places = 2): number | null {
  if (n === null || n === undefined || !Number.isFinite(n)) return null
  const f = 10 ** places
  return Math.round(n * f) / f
}

function checkMonth(month: string): void {
  if (!MONTH.test(month)) throw new Error('month must be YYYY-MM, for example 2026-11')
}

// ---- get_budget_pacing -------------------------------------------------------------------------------

interface ApiRule {
  ruleId: string
  channel: string
  scope: string
  metric: string
  comparator: string
  threshold: number
  minSample: number
  windowDays: number
  note?: string | null
  windowFrom?: string | null
  windowTo?: string | null
  observed?: number | null
  sample?: number | null
  tripped: boolean
  belowMinSample: boolean
  detail?: string | null
}

interface ApiWindow {
  from: string
  to?: string | null
  spend?: number | null
  installs?: number | null
  cpi?: number | null
}

interface ApiDay {
  day: string
  spend?: number | null
  provisionalSpend?: number | null
  cumulativeSpend: number
  expectedSpend?: number | null
}

interface ApiChannel {
  channel: string
  through?: string | null
  mtdSpend: number
  provisionalSpend: number
  installs?: number | null
  cpi?: number | null
  compliant: boolean
  complianceReason?: string | null
  rules: ApiRule[]
  marginalCpi?: { last7: ApiWindow; prior7: ApiWindow; monthToDate: ApiWindow } | null
}

interface ApiPacing {
  month: string
  capAmount?: number | null
  currency?: string | null
  through?: string | null
  daysElapsed: number
  daysInMonth: number
  mtdSpend: number
  provisionalSpend: number
  expectedSpend?: number | null
  pacePct?: number | null
  projectedSpend?: number | null
  daily: ApiDay[]
  channels: ApiChannel[]
}

/** A null cost per install is never "0": say why it is missing. */
function cpiNote(spend: number | null | undefined, installs: number | null | undefined): string {
  if (installs === 0) return 'no installs'
  if (installs === null || installs === undefined) {
    return spend === null || spend === undefined ? 'no data' : 'installs not reported'
  }
  return 'no data'
}

function compactWindow(w: ApiWindow): Obj {
  const cpi = round(w.cpi)
  return {
    from: w.from,
    to: w.to ?? null,
    spend: round(w.spend),
    installs: round(w.installs),
    cpi,
    ...(cpi === null ? { cpiNote: cpiNote(w.spend, w.installs) } : {}),
  }
}

function ruleState(r: ApiRule): string {
  if (r.tripped) return 'TRIPPED'
  if (r.belowMinSample) return 'BELOW_MIN_SAMPLE'
  if (r.observed === null || r.observed === undefined) return 'NO_DATA'
  return 'OK'
}

function compactRule(r: ApiRule): Obj {
  return {
    ruleId: r.ruleId,
    scope: r.scope,
    metric: r.metric,
    comparator: r.comparator,
    threshold: r.threshold,
    minSample: r.minSample,
    windowDays: r.windowDays,
    observed: round(r.observed, 4),
    sample: round(r.sample),
    state: ruleState(r),
    ...(r.note ? { note: r.note } : {}),
    ...(r.detail ? { detail: r.detail } : {}),
  }
}

function compactChannel(c: ApiChannel): Obj {
  const cpi = round(c.cpi)
  return {
    channel: c.channel,
    through: c.through ?? null,
    mtdSpend: round(c.mtdSpend),
    provisionalSpend: round(c.provisionalSpend),
    installs: round(c.installs),
    cpi,
    ...(cpi === null ? { cpiNote: cpiNote(c.mtdSpend, c.installs) } : {}),
    compliant: c.compliant,
    ...(c.complianceReason ? { complianceReason: c.complianceReason } : {}),
    rules: c.rules.map(compactRule),
    ...(c.marginalCpi
      ? {
          marginalCpi: {
            last7: compactWindow(c.marginalCpi.last7),
            prior7: compactWindow(c.marginalCpi.prior7),
            monthToDate: compactWindow(c.marginalCpi.monthToDate),
          },
        }
      : {}),
  }
}

function compactDay(d: ApiDay): Obj {
  return {
    day: d.day,
    spend: round(d.spend),
    ...(d.provisionalSpend !== null && d.provisionalSpend !== undefined ? { provisionalSpend: round(d.provisionalSpend) } : {}),
    cumulativeSpend: round(d.cumulativeSpend),
    expectedSpend: round(d.expectedSpend),
  }
}

export async function getBudgetPacing(config: Config, params: { month?: string }): Promise<Record<string, unknown>> {
  if (params.month !== undefined) checkMonth(params.month)
  const query = params.month ? `?month=${encodeURIComponent(params.month)}` : ''
  const p = await apiGet<ApiPacing>(`/api/v2/projects/${config.projectId}/marketing/budget${query}`, config)
  const channels = p.channels ?? []
  const tripped = channels.flatMap((c) =>
    (c.rules ?? [])
      .filter((r) => r.tripped)
      .map((r) => ({
        ruleId: r.ruleId,
        channel: c.channel,
        scope: r.scope,
        metric: r.metric,
        comparator: r.comparator,
        threshold: r.threshold,
        observed: round(r.observed, 4),
        sample: round(r.sample),
        windowFrom: r.windowFrom ?? null,
        windowTo: r.windowTo ?? null,
        ...(r.note ? { note: r.note } : {}),
        ...(r.detail ? { detail: r.detail } : {}),
      }))
  )
  const hasCap = p.capAmount !== null && p.capAmount !== undefined
  return {
    month: p.month,
    // null = no cap is set for the month; pace and expected spend are then null too, never computed against 0
    cap: hasCap ? round(p.capAmount) : null,
    currency: p.currency ?? null,
    ...(hasCap ? {} : { capNote: 'no cap set for this month: spend is shown without a pace' }),
    through: p.through ?? null,
    daysElapsed: p.daysElapsed,
    daysInMonth: p.daysInMonth,
    mtdSpend: round(p.mtdSpend),
    provisionalSpend: round(p.provisionalSpend),
    expectedSpend: round(p.expectedSpend),
    pacePct: round(p.pacePct, 4),
    projectedSpend: round(p.projectedSpend),
    nonCompliantChannels: channels.filter((c) => !c.compliant).map((c) => c.channel),
    trippedRules: tripped,
    channels: channels.map(compactChannel),
    daily: (p.daily ?? []).slice(-RECENT_DAILY_DAYS).map(compactDay),
  }
}

// ---- set_marketing_budget ----------------------------------------------------------------------------

export interface SetBudgetParams {
  month: string
  capAmount: number
  currency?: string
}

export async function setMarketingBudget(config: Config, p: SetBudgetParams): Promise<Record<string, unknown>> {
  checkMonth(p.month)
  if (typeof p.capAmount !== 'number' || !Number.isFinite(p.capAmount) || p.capAmount <= 0) {
    throw new Error('capAmount must be a number greater than 0 (a month with no cap simply has none)')
  }
  if (p.currency !== undefined && !/^[A-Za-z]{3}$/.test(p.currency)) {
    throw new Error('currency must be a three-letter code such as USD')
  }
  const saved = await apiPut<Record<string, unknown>>(
    `/api/v2/projects/${config.projectId}/marketing/budget/${encodeURIComponent(p.month)}`,
    { capAmount: p.capAmount, currency: p.currency },
    config
  )
  return { month: saved['month'], capAmount: saved['capAmount'], currency: saved['currency'] }
}

// ---- set_stop_rule / remove_stop_rule ----------------------------------------------------------------

export interface SetStopRuleParams {
  channel: string
  metric: string
  comparator: string
  threshold: number
  minSample?: number
  windowDays?: number
  scope?: string
  note?: string
}

export async function setStopRule(config: Config, p: SetStopRuleParams): Promise<Record<string, unknown>> {
  if (!p.channel?.trim()) throw new Error('channel is required (for example ADS)')
  if (!(RULE_METRICS as readonly string[]).includes(p.metric)) {
    throw new Error(`metric must be one of ${RULE_METRICS.join(', ')}`)
  }
  if (p.comparator !== 'GT' && p.comparator !== 'LT') throw new Error('comparator must be GT (above) or LT (below)')
  if (typeof p.threshold !== 'number' || !Number.isFinite(p.threshold) || p.threshold < 0) {
    throw new Error('threshold must be a number of 0 or more')
  }
  if (p.minSample !== undefined && (!Number.isInteger(p.minSample) || p.minSample < 0)) {
    throw new Error('minSample must be a whole number of 0 or more')
  }
  if (p.windowDays !== undefined && (!Number.isInteger(p.windowDays) || p.windowDays < 1 || p.windowDays > MAX_WINDOW_DAYS)) {
    throw new Error(`windowDays must be a whole number from 1 to ${MAX_WINDOW_DAYS}`)
  }
  if (p.scope !== undefined && !SCOPE.test(p.scope)) throw new Error('scope must be channel, campaign:<id> or keyword:<id>')
  const saved = await apiPost<Record<string, unknown>>(
    `/api/v2/projects/${config.projectId}/marketing/stop-rules`,
    {
      channel: p.channel,
      scope: p.scope,
      metric: p.metric,
      comparator: p.comparator,
      threshold: p.threshold,
      minSample: p.minSample,
      windowDays: p.windowDays,
      note: p.note,
    },
    config
  )
  const out: Record<string, unknown> = {
    ruleId: saved['id'],
    channel: saved['channel'],
    scope: saved['scope'],
    metric: saved['metric'],
    comparator: saved['comparator'],
    threshold: saved['threshold'],
    minSample: saved['minSample'],
    windowDays: saved['windowDays'],
  }
  if (saved['note']) out['note'] = saved['note']
  return out
}

export async function removeStopRule(config: Config, p: { ruleId: string }): Promise<Record<string, unknown>> {
  if (!p.ruleId?.trim()) throw new Error('ruleId is required (get_budget_pacing lists each rule with its ruleId)')
  await apiDelete(`/api/v2/projects/${config.projectId}/marketing/stop-rules/${encodeURIComponent(p.ruleId)}`, config)
  return { removed: p.ruleId }
}
