import { Config } from '../config.js'
import { apiGet, apiPost, apiPostWithStatus } from '../api.js'

/**
 * Knowledge Center MCP tools. The wiki is a set of versioned Markdown pages ("index.md" / "log.md" are
 * virtual) filed from an inbox of submitted sources. Flow: submit_knowledge_source feeds the inbox,
 * search_knowledge/read_knowledge_pages orient the librarian, write_knowledge_pages files the result
 * (optionally marking the source sourceIds PROCESSED atomically).
 */

export interface KnowledgePageWrite {
  path: string
  content?: string
  baseVersion?: number
  delete?: boolean
}

export interface KnowledgePageConflict {
  path: string
  currentVersion: number
  currentContent: string
}

export interface KnowledgeSkippedSource {
  sourceId: string
  reason: string
}

export async function submitKnowledgeSource(
  params: {
    sourceType: string
    sourceRef?: string
    title?: string
    contentType?: string
    payload?: string
    occurredAt?: string
    dedupKey?: string
    metadata?: Record<string, unknown>
    domain?: string
  },
  config: Config
): Promise<Record<string, unknown>> {
  const body: Record<string, unknown> = { sourceType: params.sourceType }
  if (params.sourceRef !== undefined) body['sourceRef'] = params.sourceRef
  if (params.title !== undefined) body['title'] = params.title
  if (params.contentType !== undefined) body['contentType'] = params.contentType
  if (params.payload !== undefined) body['payload'] = params.payload
  if (params.occurredAt !== undefined) body['occurredAt'] = params.occurredAt
  if (params.dedupKey !== undefined) body['dedupKey'] = params.dedupKey
  if (params.metadata !== undefined) body['metadata'] = params.metadata
  if (params.domain !== undefined) body['domain'] = params.domain

  return apiPost<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/knowledge/sources`,
    body,
    config
  )
}

/**
 * Fetch inbox sources by id. A returned source with `payload: null` and `purgedAt` set means the
 * backend's hourly retention sweep already compacted it (its payload — and any offloaded GCS object —
 * has been reclaimed once it aged past the processed-sources retention window); that's not the same as
 * an empty/missing source. `purgedAt: null` means the payload is still intact.
 */
export async function readKnowledgeSources(
  params: { ids: string[] },
  config: Config
): Promise<unknown[]> {
  const query = new URLSearchParams()
  query.set('ids', params.ids.join(','))
  const raw = await apiGet<unknown[]>(
    `/api/v1/projects/${config.projectId}/knowledge/sources?${query.toString()}`,
    config
  )
  return Array.isArray(raw) ? raw : []
}

export async function searchKnowledge(
  params: { q: string; type?: string; pathPrefix?: string; limit?: number },
  config: Config
): Promise<unknown[]> {
  const query = new URLSearchParams()
  query.set('q', params.q)
  if (params.type !== undefined) query.set('type', params.type)
  if (params.pathPrefix !== undefined) query.set('pathPrefix', params.pathPrefix)
  if (params.limit !== undefined) query.set('limit', String(params.limit))
  const raw = await apiGet<unknown[]>(
    `/api/v1/projects/${config.projectId}/knowledge/search?${query.toString()}`,
    config
  )
  return Array.isArray(raw) ? raw : []
}

export async function readKnowledgePages(
  params: { paths: string[] },
  config: Config
): Promise<unknown[]> {
  const query = new URLSearchParams()
  query.set('paths', params.paths.join(','))
  const raw = await apiGet<unknown[]>(
    `/api/v1/projects/${config.projectId}/knowledge/pages?${query.toString()}`,
    config
  )
  return Array.isArray(raw) ? raw : []
}

/**
 * The backend reports a stale-write conflict as 409 problem+json with a `conflicts` array. `api.ts`
 * folds the response body into the thrown Error's message as `API error 409: <body>` — parse that back
 * out so the conflict can be handed to the caller as data, not an exception (docs/mcp-tool-guidelines.md
 * ยง "no false promises": the agent needs the currentVersion/currentContent to act, not just a failure).
 */
function parseConflictBody(err: unknown): KnowledgePageConflict[] | null {
  if (!(err instanceof Error)) return null
  const match = err.message.match(/^API error 409: ([\s\S]*)$/)
  if (!match) return null
  try {
    const parsed = JSON.parse(match[1]) as Record<string, unknown>
    const conflicts = parsed['conflicts']
    return Array.isArray(conflicts) ? (conflicts as KnowledgePageConflict[]) : null
  } catch {
    return null
  }
}

export interface KnowledgeDomainSummary {
  slug: string
  displayName: string
  description?: string | null
  pathPrefix: string
  schemaPagePath: string
  sourceTypePatterns: string[]
  state: string
  owningAgentSlug?: string | null
}

/** Registry rows only — matches the agent-tool-provider's `list_knowledge_domains` shape on the other
 *  runtime (counts/suggestionReason are REST-only extras, not part of either tool's result). */
export async function listKnowledgeDomains(config: Config): Promise<KnowledgeDomainSummary[]> {
  const raw = await apiGet<Record<string, unknown>[]>(
    `/api/v1/projects/${config.projectId}/knowledge/domains`,
    config
  )
  if (!Array.isArray(raw)) return []
  return raw.map((d) => ({
    slug: d['slug'] as string,
    displayName: d['displayName'] as string,
    description: (d['description'] as string | null | undefined) ?? null,
    pathPrefix: d['pathPrefix'] as string,
    schemaPagePath: d['schemaPagePath'] as string,
    sourceTypePatterns: Array.isArray(d['sourceTypePatterns']) ? (d['sourceTypePatterns'] as string[]) : [],
    state: d['state'] as string,
    owningAgentSlug: (d['owningAgentSlug'] as string | null | undefined) ?? null,
  }))
}

/**
 * Claim-or-return gap report. `created` distinguishes a brand-new SUGGESTED row (true, backend 201)
 * from an existing row of any state returned as-is (false, backend 200) — the caller should treat a
 * `state: "DISMISSED"` result as "already declined, don't call again for this slug".
 */
export async function suggestKnowledgeDomain(
  params: {
    slug: string
    displayName: string
    reason: string
    description?: string
    sourceTypePatterns?: string[]
  },
  config: Config
): Promise<{ slug: string; state: string; created: boolean }> {
  const body: Record<string, unknown> = {
    slug: params.slug,
    displayName: params.displayName,
    reason: params.reason,
  }
  if (params.description !== undefined) body['description'] = params.description
  if (params.sourceTypePatterns !== undefined) body['sourceTypePatterns'] = params.sourceTypePatterns

  const { data, status } = await apiPostWithStatus<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/knowledge/domains`,
    body,
    config
  )
  return { slug: data['slug'] as string, state: data['state'] as string, created: status === 201 }
}

export async function writeKnowledgePages(
  params: { writes: KnowledgePageWrite[]; sourceIds?: string[]; skipped?: KnowledgeSkippedSource[] },
  config: Config
): Promise<Record<string, unknown>> {
  const body: Record<string, unknown> = { writes: params.writes }
  if (params.sourceIds !== undefined) body['sourceIds'] = params.sourceIds
  if (params.skipped !== undefined) body['skipped'] = params.skipped

  try {
    return await apiPost<Record<string, unknown>>(
      `/api/v1/projects/${config.projectId}/knowledge/pages/batch-write`,
      body,
      config
    )
  } catch (err) {
    const conflicts = parseConflictBody(err)
    if (conflicts) {
      return {
        conflict: true,
        conflicts,
        message:
          'Version conflict on one or more pages — re-read them with read_knowledge_pages, merge your ' +
          'changes into the returned content, and retry write_knowledge_pages once with the returned ' +
          'currentVersion as baseVersion.',
      }
    }
    throw err
  }
}
