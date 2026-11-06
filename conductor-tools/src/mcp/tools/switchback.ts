import { Config } from '../config.js'
import { apiGet, apiPost } from '../api.js'

/**
 * Switchback incrementality tests (`/marketing/switchback-tests`): one Apple Ads keyword alternated on and off by week
 * to see whether paid adds installs or only relabels organic ones. A person applies each switch (in the ad platform or
 * through Rexipe's gated tool); Conductor declares the plan, says which state each week should be in, and settles the
 * result itself into a REVIEWED change. Outputs are compacted per `docs/mcp-tool-guidelines.md`; an unknown figure stays
 * absent (`organicChangePct` is omitted when the off weeks had no organic), never 0.
 */

export const MAX_WEEK_COUNT = 12
export const MIN_WEEK_COUNT = 2

type Obj = Record<string, unknown>

export interface WeekParam {
  weekStart: string
  on: boolean
}

export interface CreateSwitchbackParams {
  name: string
  keywordId: string
  keywordText: string
  weeks?: WeekParam[]
  startWeek?: string
  weekCount?: number
  minN?: number
  minLift?: number
  maxCannibalisationPct?: number
}

interface ApiResult {
  weeks?: Array<{ weekStart: string; on: boolean; searchDownloads: number; adsNewDownloads: number; organic: number; totalInstalls: number }>
  onWeeks?: { weeks: number; meanOrganic: number; meanTotalInstalls: number }
  offWeeks?: { weeks: number; meanOrganic: number; meanTotalInstalls: number }
  n: number
  minN: number
  lift: number
  organicChangePct?: number | null
  verdict: string
  reason: string
}

interface ApiTest {
  id: string
  name: string
  keywordId: string
  keywordText: string
  status: string
  weeks: WeekParam[]
  decisionRule: { minLift: number; maxCannibalisationPct: number; incrementalIf: string }
  minN: number
  verdict?: string | null
  currentWeek: { state: string; weekStart?: string | null; weekEnd?: string | null }
  waitingFor?: string | null
  result?: ApiResult | null
  changeWorkItemId?: string | null
}

function base(config: Config): string {
  return `/api/v2/projects/${config.projectId}/marketing/switchback-tests`
}

const DATE = /^\d{4}-\d{2}-\d{2}$/

/** Alternating weeks from `startWeek` (a Monday), the first one ON. */
export function alternatingWeeks(startWeek: string, weekCount: number): WeekParam[] {
  if (!DATE.test(startWeek)) throw new Error('startWeek must be a date, yyyy-MM-dd')
  const start = new Date(`${startWeek}T00:00:00Z`)
  if (Number.isNaN(start.getTime())) throw new Error('startWeek must be a real date, yyyy-MM-dd')
  if (start.getUTCDay() !== 1) throw new Error(`startWeek ${startWeek} is not a Monday`)
  if (!Number.isInteger(weekCount) || weekCount < MIN_WEEK_COUNT || weekCount > MAX_WEEK_COUNT) {
    throw new Error(`weekCount must be a whole number from ${MIN_WEEK_COUNT} to ${MAX_WEEK_COUNT}`)
  }
  return Array.from({ length: weekCount }, (_, i) => {
    const d = new Date(start.getTime() + i * 7 * 86_400_000)
    return { weekStart: d.toISOString().slice(0, 10), on: i % 2 === 0 }
  })
}

function resolveWeeks(p: CreateSwitchbackParams): WeekParam[] {
  const hasList = p.weeks !== undefined
  const hasGenerated = p.startWeek !== undefined || p.weekCount !== undefined
  if (hasList && hasGenerated) throw new Error('Give either weeks or startWeek with weekCount, not both')
  if (hasList) {
    if (!Array.isArray(p.weeks) || p.weeks.some((w) => typeof w?.weekStart !== 'string' || typeof w?.on !== 'boolean')) {
      throw new Error('Each week needs weekStart (a Monday, yyyy-MM-dd) and on (true or false)')
    }
    return p.weeks
  }
  if (p.startWeek === undefined || p.weekCount === undefined) {
    throw new Error('Give weeks, or startWeek together with weekCount')
  }
  return alternatingWeeks(p.startWeek, p.weekCount)
}

function compactResult(r: ApiResult): Obj {
  const out: Obj = {
    verdict: r.verdict,
    reason: r.reason,
    n: r.n,
    minN: r.minN,
    lift: r.lift,
    onWeeks: r.onWeeks,
    offWeeks: r.offWeeks,
    weeks: (r.weeks ?? []).map(
      (w) => `${w.weekStart} ${w.on ? 'ON' : 'OFF'}: search ${w.searchDownloads}, ads ${w.adsNewDownloads}, organic ${w.organic}, total ${w.totalInstalls}`
    ),
  }
  if (typeof r.organicChangePct === 'number') out['organicChangePct'] = r.organicChangePct
  return out
}

function compactTest(t: ApiTest): Obj {
  const out: Obj = {
    id: t.id,
    name: t.name,
    keyword: `${t.keywordText} (id ${t.keywordId})`,
    status: t.status,
    thisWeek: t.currentWeek.weekStart ? `${t.currentWeek.state} (${t.currentWeek.weekStart} to ${t.currentWeek.weekEnd})` : t.currentWeek.state,
    weeks: t.weeks.map((w) => `${w.weekStart}:${w.on ? 'ON' : 'OFF'}`),
    rule: t.decisionRule.incrementalIf,
    minN: t.minN,
  }
  if (t.verdict) out['verdict'] = t.verdict
  if (t.waitingFor) out['waitingFor'] = t.waitingFor
  if (t.result) out['result'] = compactResult(t.result)
  if (t.changeWorkItemId) out['changeWorkItemId'] = t.changeWorkItemId
  return out
}

export async function createSwitchbackTest(config: Config, p: CreateSwitchbackParams): Promise<Record<string, unknown>> {
  const weeks = resolveWeeks(p)
  const body: Obj = { name: p.name, keywordId: p.keywordId, keywordText: p.keywordText, weeks }
  if (p.minN !== undefined) body['minN'] = p.minN
  const rule: Obj = {}
  if (p.minLift !== undefined) rule['minLift'] = p.minLift
  if (p.maxCannibalisationPct !== undefined) rule['maxCannibalisationPct'] = p.maxCannibalisationPct
  if (Object.keys(rule).length > 0) body['decisionRule'] = rule
  return compactTest(await apiPost<ApiTest>(base(config), body, config))
}

export async function startSwitchbackTest(config: Config, p: { id: string }): Promise<Record<string, unknown>> {
  return compactTest(await apiPost<ApiTest>(`${base(config)}/${encodeURIComponent(p.id)}/start`, {}, config))
}

export async function getSwitchbackTest(config: Config, p: { id: string }): Promise<Record<string, unknown>> {
  return compactTest(await apiGet<ApiTest>(`${base(config)}/${encodeURIComponent(p.id)}`, config))
}

export async function listSwitchbackTests(config: Config): Promise<Record<string, unknown>> {
  const r = await apiGet<{ tests?: ApiTest[] }>(base(config), config)
  return {
    tests: (r.tests ?? []).map((t) => {
      const out: Obj = {
        id: t.id,
        name: t.name,
        keyword: t.keywordText,
        status: t.status,
        thisWeek: t.currentWeek.state,
      }
      if (t.verdict) out['verdict'] = t.verdict
      if (t.waitingFor) out['waitingFor'] = t.waitingFor
      return out
    }),
  }
}
