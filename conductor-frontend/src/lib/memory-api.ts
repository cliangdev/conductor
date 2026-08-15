import { apiDelete, apiGet, apiPatch, apiPost } from '@/lib/api'

// Client for the agent-memory REST surface (see docs/memory.md) — the same `agent_memories` rows
// the ReAct loop reads via DatabaseMemoryAugmentor and writes via the extraction/consolidation
// passes. This module is a UI/API lens only; it never triggers extraction or consolidation.

export type MemoryType = 'fact' | 'decision' | 'preference' | 'event'

/** The view's derived tri-state, not the stored entity status — `superseded` means the memory's
 *  validity window is closed (`validTo` set), regardless of whether it was raw or active before
 *  closing. */
export type MemoryStatus = 'raw' | 'active' | 'superseded'

export interface MemoryView {
  id: string
  content: string
  type: MemoryType
  status: MemoryStatus
  importance: number
  /** Null for human-authored (manually created) memories. */
  agentId?: string | null
  sourceConversationId?: string | null
  validFrom: string
  validTo?: string | null
  /** Id of the memory that replaced this one, when superseded. */
  supersededBy?: string | null
  /** When a raw extraction was promoted to active by consolidation. Null for rows that were never raw. */
  promotedAt?: string | null
  accessCount: number
  lastAccessedAt?: string | null
  createdAt: string
}

export interface MemoryDetailView extends MemoryView {
  /** Supersession ancestors of this memory, newest first. Empty when never superseded. */
  history: MemoryView[]
}

export interface MemoryListResponse {
  items: MemoryView[]
  total: number
}

/** Memory counts by lifecycle bucket — for a UI badge without a full list call. */
export interface MemoryCounts {
  /** raw + consolidated (active) — the count of rows with an open validity window. */
  liveTotal: number
  raw: number
  consolidated: number
  superseded: number
}

export interface ListMemoriesOptions {
  q?: string
  status?: MemoryStatus
  type?: MemoryType
  agentId?: string
  limit?: number
  offset?: number
}

export function listMemories(
  projectId: string,
  token: string,
  opts?: ListMemoriesOptions,
): Promise<MemoryListResponse> {
  const params = new URLSearchParams()
  if (opts?.q) params.set('q', opts.q)
  if (opts?.status) params.set('status', opts.status)
  if (opts?.type) params.set('type', opts.type)
  if (opts?.agentId) params.set('agentId', opts.agentId)
  if (opts?.limit) params.set('limit', String(opts.limit))
  if (opts?.offset) params.set('offset', String(opts.offset))
  const qs = params.toString()
  return apiGet<MemoryListResponse>(`/api/v1/projects/${projectId}/memories${qs ? `?${qs}` : ''}`, token)
}

export function getMemoryCounts(projectId: string, token: string): Promise<MemoryCounts> {
  return apiGet<MemoryCounts>(`/api/v1/projects/${projectId}/memories/counts`, token)
}

export interface CreateMemoryRequest {
  content: string
  type: MemoryType
  /** 1-10, defaults to 5 server-side when omitted. */
  importance?: number
}

/** Human-authored memory — lands ACTIVE immediately (no consolidation pass) and carries no agent
 *  attribution (`agentId`/`sourceConversationId` are null), distinct from the raw extractions an
 *  agent's post-turn pipeline writes. */
export function createMemory(
  projectId: string,
  request: CreateMemoryRequest,
  token: string,
): Promise<MemoryView> {
  return apiPost<MemoryView>(`/api/v1/projects/${projectId}/memories`, request, token)
}

export function getMemory(projectId: string, memoryId: string, token: string): Promise<MemoryDetailView> {
  return apiGet<MemoryDetailView>(`/api/v1/projects/${projectId}/memories/${memoryId}`, token)
}

export interface UpdateMemoryRequest {
  content?: string
  type?: MemoryType
  importance?: number
}

/** All-optional partial update — omit a field to leave it unchanged. 409s if the memory is already
 *  superseded — a closed row is history, not a live document. */
export function updateMemory(
  projectId: string,
  memoryId: string,
  request: UpdateMemoryRequest,
  token: string,
): Promise<MemoryView> {
  return apiPatch<MemoryView>(
    `/api/v1/projects/${projectId}/memories/${memoryId}`,
    request,
    token,
  ) as Promise<MemoryView>
}

export function deleteMemory(projectId: string, memoryId: string, token: string): Promise<void> {
  return apiDelete(`/api/v1/projects/${projectId}/memories/${memoryId}`, token)
}
