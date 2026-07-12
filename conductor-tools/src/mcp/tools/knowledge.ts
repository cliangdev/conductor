import { Config } from '../config.js'
import { apiGet, apiPost } from '../api.js'

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

  return apiPost<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/knowledge/sources`,
    body,
    config
  )
}

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

export async function writeKnowledgePages(
  params: { writes: KnowledgePageWrite[]; sourceIds?: string[] },
  config: Config
): Promise<Record<string, unknown>> {
  const body: Record<string, unknown> = { writes: params.writes }
  if (params.sourceIds !== undefined) body['sourceIds'] = params.sourceIds

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
