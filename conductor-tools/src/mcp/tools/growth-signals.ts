import { Config } from '../config.js'
import { apiGet, apiPost, apiPut, apiDelete } from '../api.js'
import { trimReportValue } from './marketing-report.js'

/**
 * Growth signals (`/marketing/growth-signals` and its write endpoints): the seasonal moments calendar, the weekly
 * ratings program and the competitor watch, all from public data. Outputs are compacted per
 * `docs/mcp-tool-guidelines.md`: nulls and empties dropped, review text shortened, dates not times. A missing value
 * stays absent, never 0; `weeklyGrowth` is kept as an explicit null so "no previous snapshot" is not read as "no change".
 */

export const MAX_REVIEW_BODY = 240

type Json = unknown
type Obj = Record<string, Json>

const DATE = /^\d{4}-\d{2}-\d{2}$/
const MOMENT_KEY = /^[a-z0-9][a-z0-9_-]{0,63}$/
const APP_ID = /^\d{1,20}$/
const COUNTRY = /^[A-Za-z]{2}$/

function projectBase(config: Config): string {
  return `/api/v2/projects/${config.projectId}/marketing`
}

function checkDate(value: unknown, name: string): string {
  if (typeof value !== 'string' || !DATE.test(value)) throw new Error(`${name} must be a date, YYYY-MM-DD`)
  return value
}

function checkKey(key: unknown): string {
  const k = typeof key === 'string' ? key.trim().toLowerCase() : ''
  if (!MOMENT_KEY.test(k)) {
    throw new Error('key must be 1-64 lower-case letters, digits, hyphens or underscores, e.g. thanksgiving-2026')
  }
  return k
}

function checkAppId(appId: unknown): string {
  const id = typeof appId === 'string' ? appId.trim() : typeof appId === 'number' ? String(appId) : ''
  if (!APP_ID.test(id)) throw new Error('appId must be the numeric App Store id, digits only')
  return id
}

function checkCountry(country: unknown): string | undefined {
  if (country === undefined || country === null || country === '') return undefined
  if (typeof country !== 'string' || !COUNTRY.test(country.trim())) throw new Error('country must be two letters, e.g. us')
  return country.trim().toLowerCase()
}

function shorten(text: unknown): string | undefined {
  if (typeof text !== 'string' || text === '') return undefined
  const flat = text.replace(/\s+/g, ' ').trim()
  return flat.length > MAX_REVIEW_BODY ? `${flat.slice(0, MAX_REVIEW_BODY)}...` : flat
}

function compactReview(review: Obj): Obj {
  const reviewedAt = review['reviewedAt']
  return {
    rating: review['rating'],
    title: review['title'],
    body: shorten(review['body']),
    author: review['author'],
    date: typeof reviewedAt === 'string' ? reviewedAt.slice(0, 10) : undefined,
  }
}

/** A rating with nulls dropped, except weeklyGrowth, which stays an explicit null until two snapshots exist. */
function compactRating(rating: Obj): Obj {
  const trimmed = trimReportValue(rating) as Obj
  return { ...trimmed, weeklyGrowth: rating['weeklyGrowth'] ?? null }
}

interface GrowthPayload extends Obj {
  moments?: Obj[]
  ratings?: { ours?: Obj | null; competitors?: Obj[] }
  reviews?: { ours?: Obj[]; competitors?: Array<Obj & { reviews?: Obj[] }> }
  competitorChanges?: Obj[]
  keywordPresence?: Obj[]
  trackedKeywords?: Obj
}

export async function getGrowthSignals(config: Config): Promise<Record<string, unknown>> {
  const g = await apiGet<GrowthPayload>(`${projectBase(config)}/growth-signals`, config)
  const ours = g.ratings?.ours
  const out: Record<string, unknown> = {
    moments: trimReportValue(g.moments ?? []),
    ratings: {
      ...(ours ? { ours: compactRating(ours) } : {}),
      competitors: (g.ratings?.competitors ?? []).map(compactRating),
    },
    reviews: {
      ours: (g.reviews?.ours ?? []).map((r) => trimReportValue(compactReview(r))),
      competitors: (g.reviews?.competitors ?? []).map((c) =>
        trimReportValue({
          appId: c['appId'],
          name: c['name'],
          reviews: (c.reviews ?? []).map(compactReview),
        })
      ),
    },
    competitorChanges: trimReportValue(g.competitorChanges ?? []),
    keywordPresence: trimReportValue(g.keywordPresence ?? []),
  }
  if (g.trackedKeywords) out['trackedKeywords'] = trimReportValue(g.trackedKeywords)
  return out
}

export interface SetMomentParams {
  key: string
  name: string
  date: string
  leadDays?: number
  surfaces: Array<{ surface: string; dueOn: string }>
}

export async function setMarketingMoment(config: Config, p: SetMomentParams): Promise<Record<string, unknown>> {
  const key = checkKey(p.key)
  if (typeof p.name !== 'string' || !p.name.trim()) throw new Error('name is required')
  const date = checkDate(p.date, 'date')
  if (p.leadDays !== undefined && (!Number.isInteger(p.leadDays) || p.leadDays < 0 || p.leadDays > 365)) {
    throw new Error('leadDays must be a whole number from 0 to 365')
  }
  if (!Array.isArray(p.surfaces)) throw new Error('surfaces must be a list of {surface, dueOn}')
  const surfaces = p.surfaces.map((s, i) => {
    if (!s || typeof s.surface !== 'string' || !s.surface.trim()) throw new Error(`surfaces[${i}].surface is required`)
    return { surface: s.surface.trim(), dueOn: checkDate(s.dueOn, `surfaces[${i}].dueOn`) }
  })
  const saved = await apiPut<Obj>(
    `${projectBase(config)}/moments/${encodeURIComponent(key)}`,
    { name: p.name.trim(), date, leadDays: p.leadDays, surfaces },
    config
  )
  return trimReportValue(saved) as Record<string, unknown>
}

export async function linkMomentChange(
  config: Config,
  p: { key: string; surfaceId: string; workItemId: string }
): Promise<Record<string, unknown>> {
  const key = checkKey(p.key)
  if (typeof p.surfaceId !== 'string' || !p.surfaceId.trim()) throw new Error('surfaceId is required (from get_growth_signals)')
  if (typeof p.workItemId !== 'string' || !p.workItemId.trim()) throw new Error('workItemId is required')
  const saved = await apiPost<Obj>(
    `${projectBase(config)}/moments/${encodeURIComponent(key)}/surfaces/${encodeURIComponent(p.surfaceId.trim())}/change`,
    { workItemId: p.workItemId.trim() },
    config
  )
  return trimReportValue(saved) as Record<string, unknown>
}

export async function addCompetitor(
  config: Config,
  p: { appId: string; name: string; country?: string }
): Promise<Record<string, unknown>> {
  const appId = checkAppId(p.appId)
  if (typeof p.name !== 'string' || !p.name.trim()) throw new Error('name is required')
  const saved = await apiPost<Obj>(
    `${projectBase(config)}/competitors`,
    { appId, name: p.name.trim(), country: checkCountry(p.country) },
    config
  )
  return trimReportValue(saved) as Record<string, unknown>
}

export async function removeCompetitor(config: Config, p: { appId: string }): Promise<Record<string, unknown>> {
  const appId = checkAppId(p.appId)
  await apiDelete(`${projectBase(config)}/competitors?appId=${encodeURIComponent(appId)}`, config)
  return { removed: appId }
}

export async function setTrackedKeywords(
  config: Config,
  p: { keywords: string[]; country?: string }
): Promise<Record<string, unknown>> {
  if (!Array.isArray(p.keywords) || p.keywords.some((k) => typeof k !== 'string')) {
    throw new Error('keywords must be a list of strings (an empty list clears it)')
  }
  if (p.keywords.length > 25) throw new Error('At most 25 keywords are tracked')
  const saved = await apiPut<Obj>(
    `${projectBase(config)}/tracked-keywords`,
    { keywords: p.keywords, country: checkCountry(p.country) },
    config
  )
  return trimReportValue(saved) as Record<string, unknown>
}
