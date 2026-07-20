import { apiGet, apiPatch, apiPost } from '@/lib/api'

export interface KnowledgePageView {
  path: string
  /** 0 for generated virtual pages (index.md, log.md). */
  version: number
  type: string
  title?: string | null
  description?: string | null
  /** Canonical render: frontmatter + body. */
  content?: string
}

export interface KnowledgeSearchHit {
  path: string
  type: string
  title?: string | null
  description?: string | null
  snippet?: string | null
  rank: number
}

export type KnowledgeChangeKind = 'CREATE' | 'UPDATE' | 'DELETE'

export interface KnowledgeActor {
  /** Free-form discriminator, e.g. user, workflow, librarian. */
  kind?: string
  id?: string | null
  workflowRunId?: string | null
}

export interface KnowledgePageRevisionView {
  version: number
  changeKind: KnowledgeChangeKind
  actor?: KnowledgeActor
  createdAt: string
  sourceRefs?: string[]
}

export function getKnowledgeIndex(projectId: string, token: string): Promise<KnowledgePageView> {
  return apiGet<KnowledgePageView>(`/api/v1/projects/${projectId}/knowledge/index`, token)
}

export function getKnowledgePages(
  projectId: string,
  paths: string[],
  token: string,
): Promise<KnowledgePageView[]> {
  const query = paths.map((p) => encodeURIComponent(p)).join(',')
  return apiGet<KnowledgePageView[]>(`/api/v1/projects/${projectId}/knowledge/pages?paths=${query}`, token)
}

/** Multi-get semantics mean an unknown/deleted path is silently omitted — resolves to null in that case. */
export async function getKnowledgePage(
  projectId: string,
  path: string,
  token: string,
): Promise<KnowledgePageView | null> {
  const pages = await getKnowledgePages(projectId, [path], token)
  return pages[0] ?? null
}

export function searchKnowledge(
  projectId: string,
  q: string,
  token: string,
  opts?: { type?: string; pathPrefix?: string; limit?: number },
): Promise<KnowledgeSearchHit[]> {
  const params = new URLSearchParams({ q })
  if (opts?.type) params.set('type', opts.type)
  if (opts?.pathPrefix) params.set('pathPrefix', opts.pathPrefix)
  if (opts?.limit) params.set('limit', String(opts.limit))
  return apiGet<KnowledgeSearchHit[]>(`/api/v1/projects/${projectId}/knowledge/search?${params.toString()}`, token)
}

/** Admin-only: turns on the Knowledge Center ingestion pipeline for this workspace. */
export function enableKnowledge(projectId: string, token: string): Promise<{ knowledgeEnabled: boolean }> {
  return apiPatch<{ knowledgeEnabled: boolean }>(
    `/api/v1/projects/${projectId}/settings`,
    { knowledgeEnabled: true },
    token,
  ) as Promise<{ knowledgeEnabled: boolean }>
}

/** ADMIN-only ops recovery: resets every DEAD source in the project back to PENDING for the scheduler
 *  to re-claim. Returns the number of sources reset. */
export function retryDeadKnowledgeSources(projectId: string, token: string): Promise<{ retried: number }> {
  return apiPost<{ retried: number }>(`/api/v1/projects/${projectId}/knowledge/sources/retry`, {}, token)
}

// ── Ingestion inbox (sources) ───────────────────────────────────────────────

export type KnowledgeSourceStatus = 'PENDING' | 'PROCESSING' | 'PROCESSED' | 'DEAD'

export interface KnowledgeSourceCounts {
  pending: number
  processing: number
  processed: number
  dead: number
}

/** Cheap per-status inbox summary — for the pipeline strip's badge/count row. */
export function getKnowledgeSourceCounts(projectId: string, token: string): Promise<KnowledgeSourceCounts> {
  return apiGet<KnowledgeSourceCounts>(`/api/v1/projects/${projectId}/knowledge/sources/counts`, token)
}

export interface KnowledgeSourceOrigin {
  kind?: string
  id?: string
}

export interface KnowledgeSourceDto {
  id: string
  projectId: string
  sourceType: string
  sourceRef?: string | null
  title?: string | null
  contentType?: string | null
  payload?: string | null
  payloadOffloaded: boolean
  metadata?: Record<string, unknown> | null
  origin?: KnowledgeSourceOrigin | null
  occurredAt?: string | null
  receivedAt: string
  status: KnowledgeSourceStatus
  attempts: number
  errorMessage?: string | null
  /** Set once retention has compacted this source's payload; null means it's still intact. */
  purgedAt?: string | null
  /** The domain lane this source was routed to at submit time. Null is the unclassified/generalist lane. */
  domain?: string | null
}

/** Browse the inbox filtered by status (default PENDING) — never resolves offloaded payload content. */
export function listKnowledgeSources(
  projectId: string,
  token: string,
  opts?: { status?: KnowledgeSourceStatus; domain?: string },
): Promise<KnowledgeSourceDto[]> {
  const status = opts?.status ?? 'PENDING'
  const params = new URLSearchParams({ status })
  if (opts?.domain) params.set('domain', opts.domain)
  return apiGet<KnowledgeSourceDto[]>(
    `/api/v1/projects/${projectId}/knowledge/sources?${params.toString()}`,
    token,
  )
}

export function listKnowledgeRevisions(
  projectId: string,
  path: string,
  token: string,
): Promise<KnowledgePageRevisionView[]> {
  return apiGet<KnowledgePageRevisionView[]>(
    `/api/v1/projects/${projectId}/knowledge/revisions?path=${encodeURIComponent(path)}`,
    token,
  )
}

// ── Domains ──────────────────────────────────────────────────────────────────

export type KnowledgeDomainState = 'ACTIVE' | 'SUGGESTED' | 'DISMISSED'

export interface KnowledgeDomainDto {
  slug: string
  displayName: string
  description?: string | null
  pathPrefix: string
  schemaPagePath: string
  sourceTypePatterns: string[]
  owningAgentSlug?: string | null
  state: KnowledgeDomainState
  suggestionReason?: string | null
  pendingCount: number
  processingCount: number
  processedCount: number
}

/** Every domain row (ACTIVE, plus any SUGGESTED gap report or DISMISSED past suggestion), slug-ordered. */
export function listKnowledgeDomains(projectId: string, token: string): Promise<KnowledgeDomainDto[]> {
  return apiGet<KnowledgeDomainDto[]>(`/api/v1/projects/${projectId}/knowledge/domains`, token)
}

export interface UpdateKnowledgeDomainRequest {
  displayName?: string
  description?: string
  sourceTypePatterns?: string[]
  /** Assigns this agent slug (must exist in the project). To clear an assignment, use clearOwningAgent instead. */
  owningAgentSlug?: string
  /** Set true to clear an existing owningAgentSlug assignment; takes precedence over owningAgentSlug. */
  clearOwningAgent?: boolean
  state?: KnowledgeDomainState
}

/** ADMIN-only partial update — omit a field to leave it unchanged (see UpdateKnowledgeDomainRequest). */
export function updateKnowledgeDomain(
  projectId: string,
  slug: string,
  request: UpdateKnowledgeDomainRequest,
  token: string,
): Promise<KnowledgeDomainDto> {
  return apiPatch<KnowledgeDomainDto>(
    `/api/v1/projects/${projectId}/knowledge/domains/${encodeURIComponent(slug)}`,
    request,
    token,
  ) as Promise<KnowledgeDomainDto>
}

/**
 * ADMIN-only. Creates (or reuses) the `knowledge-<slug>` specialist agent and assigns it as the
 * domain's owningAgentSlug. Idempotent — safe to call again if the agent already exists.
 */
export function createKnowledgeDomainSpecialist(
  projectId: string,
  slug: string,
  token: string,
): Promise<KnowledgeDomainDto> {
  return apiPost<KnowledgeDomainDto>(
    `/api/v1/projects/${projectId}/knowledge/domains/${encodeURIComponent(slug)}/specialist`,
    {},
    token,
  )
}

export interface CreateKnowledgeDomainRequest {
  slug: string
  displayName: string
  description?: string
  sourceTypePatterns?: string[]
  reason?: string
}

/** Raises a gap report — claim-or-return on slug, always lands (or returns) SUGGESTED for a new row. */
export function createKnowledgeDomain(
  projectId: string,
  request: CreateKnowledgeDomainRequest,
  token: string,
): Promise<KnowledgeDomainDto> {
  return apiPost<KnowledgeDomainDto>(`/api/v1/projects/${projectId}/knowledge/domains`, request, token)
}
