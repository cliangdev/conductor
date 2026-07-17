import { apiGet, apiPatch } from '@/lib/api'

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
