import { Config } from '../config.js'
import { apiGet, apiPost } from '../api.js'

/**
 * Tracked App Store campaign links (`/marketing/links`): one per Post (made automatically when a Post is created),
 * and by hand for a bio, a creator, an email or anything else. A link's token is the `ct` of the URL; Apple reports
 * first-time downloads per token but drops tokens below about 5, so tokens roll up by family. A family Apple
 * reported nothing for is `downloads: null` with `belowThreshold: true`, never 0 (`docs/mcp-tool-guidelines.md`:
 * absent stays absent, a null that means something is kept explicit).
 */

export const LINK_KINDS = ['POST', 'BIO', 'CREATOR', 'EMAIL', 'OTHER'] as const
export const MAX_LABEL = 200
export const LIST_LIMIT = 50

const TOKEN = /^[a-z0-9][a-z0-9-]{0,39}$/
const FAMILY = /^[a-z0-9][a-z0-9-]{0,63}$/

type Obj = Record<string, unknown>

interface ApiLink {
  id: string
  kind: string
  family: string
  token: string
  url: string
  label?: string | null
  workItemId?: string | null
}

interface ApiFamily {
  family: string
  kind: string
  tokens: number
  downloads?: number | null
  belowThreshold: boolean
  note?: string | null
  views?: number | null
}

interface ApiPerformance {
  from: string
  to: string
  families: ApiFamily[]
}

function checkKind(kind: string | undefined): string {
  const normalised = (kind ?? '').trim().toUpperCase()
  if (!(LINK_KINDS as readonly string[]).includes(normalised)) {
    throw new Error(`kind must be one of ${LINK_KINDS.join(', ')}`)
  }
  return normalised
}

function compactLink(l: ApiLink): Obj {
  return {
    id: l.id,
    kind: l.kind,
    family: l.family,
    token: l.token,
    url: l.url,
    ...(l.label ? { label: l.label } : {}),
    ...(l.workItemId ? { workItemId: l.workItemId } : {}),
  }
}

function compactFamily(f: ApiFamily): Obj {
  return {
    family: f.family,
    kind: f.kind,
    tokens: f.tokens,
    // null = Apple reported nothing for this family in the window (below its threshold), which is not 0
    downloads: f.downloads ?? null,
    belowThreshold: f.belowThreshold,
    ...(f.note ? { note: f.note } : {}),
    views: f.views ?? null,
  }
}

// ---- create_tracked_link -----------------------------------------------------------------------------

export interface CreateTrackedLinkParams {
  kind: string
  family: string
  token: string
  label?: string
  workItemId?: string
}

export async function createTrackedLink(config: Config, p: CreateTrackedLinkParams): Promise<Record<string, unknown>> {
  const kind = checkKind(p.kind)
  if (typeof p.family !== 'string' || !FAMILY.test(p.family)) {
    throw new Error('family must be lowercase letters, digits and dashes, starting with a letter or digit, at most 64 characters')
  }
  if (typeof p.token !== 'string' || !TOKEN.test(p.token)) {
    throw new Error('token must be lowercase letters, digits and dashes, starting with a letter or digit, at most 40 characters')
  }
  if (p.label !== undefined && p.label.length > MAX_LABEL) throw new Error(`label must be at most ${MAX_LABEL} characters`)
  if (kind === 'POST' && !p.workItemId?.trim()) {
    throw new Error('workItemId is required for a POST link: name the Post it belongs to')
  }
  const saved = await apiPost<ApiLink>(
    `/api/v2/projects/${config.projectId}/marketing/links`,
    { kind, family: p.family, token: p.token, label: p.label, workItemId: p.workItemId },
    config
  )
  return {
    ...compactLink(saved),
    nextStep:
      'Use the url wherever this link is shared. Apple reports its downloads by token a day or two later; ' +
      'list_tracked_links({withPerformance: true}) shows the family total.',
  }
}

// ---- list_tracked_links ------------------------------------------------------------------------------

export interface ListTrackedLinksParams {
  kind?: string
  workItemId?: string
  withPerformance?: boolean
}

export async function listTrackedLinks(config: Config, p: ListTrackedLinksParams): Promise<Record<string, unknown>> {
  const kind = p.kind === undefined ? undefined : checkKind(p.kind)
  const query = [
    kind ? `kind=${encodeURIComponent(kind)}` : '',
    p.workItemId ? `workItemId=${encodeURIComponent(p.workItemId)}` : '',
    `limit=${LIST_LIMIT}`,
  ]
    .filter(Boolean)
    .join('&')
  const page = await apiGet<{ links?: ApiLink[]; total?: number }>(
    `/api/v2/projects/${config.projectId}/marketing/links?${query}`,
    config
  )
  const links = page.links ?? []
  const total = page.total ?? links.length
  const result: Obj = { total, links: links.map(compactLink) }
  if (total > links.length) {
    result['note'] = `Showing the newest ${links.length} of ${total}: filter by kind or workItemId to narrow.`
  }
  if (p.withPerformance) {
    const performance = await apiGet<ApiPerformance>(
      `/api/v2/projects/${config.projectId}/marketing/links/performance`,
      config
    )
    // A filtered list shows only the families of the links it returned; an unfiltered one shows them all.
    const wanted = kind || p.workItemId ? new Set(links.map((l) => l.family)) : null
    result['performance'] = {
      from: performance.from,
      to: performance.to,
      families: (performance.families ?? []).filter((f) => wanted === null || wanted.has(f.family)).map(compactFamily),
    }
  }
  return result
}
