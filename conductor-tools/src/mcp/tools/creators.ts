import { Config } from '../config.js'
import { apiGet } from '../api.js'

/**
 * Creator results (`/marketing/creators/{id}/results`): what one creator (a Work Item on the CREATORS workflow)
 * has produced: its posts with their views, the downloads of its campaign links and the redemptions of its offer
 * code. Compacted per `docs/mcp-tool-guidelines.md`. A figure the sources did not report stays an explicit null
 * with its note, never 0: Apple omits campaign rows under its reporting threshold and a product backend may not
 * send redemptions at all. A handle or offer code that is not set is left out.
 */

export const MAX_CREATOR_POSTS = 20

const DATE = /^\d{4}-\d{2}-\d{2}$/

type Obj = Record<string, unknown>

interface ApiPost {
  workItemId: string
  displayId: string
  title: string
  status: string
  views?: number | null
}

interface ApiResults {
  window: { from: string; to: string }
  creator: { workItemId: string; displayId: string; handle?: string | null; status: string }
  postCount: number
  posts: ApiPost[]
  linkDownloads: { tokens: string[]; downloads?: number | null; belowThreshold: boolean; note?: string | null }
  offerRedemptions: { code?: string | null; value?: number | null; note?: string | null }
}

/** The object with the listed keys dropped when they are null or undefined. Everything else is kept as is. */
function withoutNulls(obj: Obj, keys: string[]): Obj {
  const out: Obj = { ...obj }
  for (const key of keys) {
    if (out[key] === null || out[key] === undefined) delete out[key]
  }
  return out
}

function checkDate(value: string | undefined, name: string): string | undefined {
  if (value === undefined || value === '') return undefined
  if (!DATE.test(value)) throw new Error(`${name} must be a real date, YYYY-MM-DD`)
  const parsed = new Date(`${value}T00:00:00Z`)
  // 2026-02-30 parses in some engines by rolling over; reading it back catches that too
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) {
    throw new Error(`${name} must be a real date, YYYY-MM-DD`)
  }
  return value
}

function query(from: string | undefined, to: string | undefined): string {
  const parts: string[] = []
  if (from) parts.push(`from=${from}`)
  if (to) parts.push(`to=${to}`)
  return parts.length > 0 ? `?${parts.join('&')}` : ''
}

export async function getCreatorResults(
  config: Config,
  params: { creatorId: string; from?: string; to?: string }
): Promise<Record<string, unknown>> {
  const creatorId = typeof params.creatorId === 'string' ? params.creatorId.trim() : ''
  if (!creatorId) throw new Error('creatorId is required: the creator Work Item id or display id, e.g. RE-12')
  const from = checkDate(params.from, 'from')
  const to = checkDate(params.to, 'to')
  if (from && to && from > to) throw new Error(`from ${from} is after to ${to}: a window runs from the earlier day to the later one`)

  const r = await apiGet<ApiResults>(
    `/api/v2/projects/${config.projectId}/marketing/creators/${encodeURIComponent(creatorId)}/results${query(from, to)}`,
    config
  )

  return {
    window: { from: r.window.from, to: r.window.to },
    creator: withoutNulls(
      { id: r.creator.workItemId, displayId: r.creator.displayId, handle: r.creator.handle, status: r.creator.status },
      ['handle']
    ),
    postCount: r.postCount,
    posts: (r.posts ?? []).slice(0, MAX_CREATOR_POSTS).map((p) => ({
      id: p.workItemId,
      displayId: p.displayId,
      title: p.title,
      status: p.status,
      views: p.views ?? null,
    })),
    linkDownloads: withoutNulls(
      {
        tokens: r.linkDownloads.tokens,
        downloads: r.linkDownloads.downloads ?? null,
        belowThreshold: r.linkDownloads.belowThreshold,
        note: r.linkDownloads.note,
      },
      ['note']
    ),
    offerRedemptions: withoutNulls(
      { code: r.offerRedemptions.code, value: r.offerRedemptions.value ?? null, note: r.offerRedemptions.note },
      ['code', 'note']
    ),
  }
}
