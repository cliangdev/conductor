'use client'

// COND-18: the single source of Workflow display metadata for the UI.
//
// `WorkflowView`s are fetched per slug and cached at module scope (workflows change rarely, and the
// issues table renders one StatusDropdown per row — without a shared cache that would be N identical
// fetches). All status label/color/category rendering flows through the helpers here so there is one
// place that maps a Workflow-defined status to how it looks.

import pluralize from 'pluralize'
import { useEffect, useState } from 'react'
import { apiGet, apiPost, apiPut } from '@/lib/api'
import type { BadgeProps } from '@/components/ui/badge'
import type {
  WorkflowView,
  WorkflowStatusCategory,
  WorkflowVersionsResponse,
} from '@/types/workItem'
import type {
  WorkflowCreateResponse,
  WorkflowDefinitionDto,
} from '@/types/workflow'
import type { StatechartDefinition } from '@/lib/workflowDefinition'
import type { Member } from '@/types'

/** The project's default Workflow. Work Items with no explicit slug resolve to this. */
export const DEFAULT_WORKFLOW_SLUG = 'ENGINEERING'

// ── WorkflowView cache ──────────────────────────────────────────────────────
//
// Two-tier: module-scope Map (fast within session) + localStorage (instant on page refresh).
// WorkflowViews change only when a workflow is published, so explicit invalidation is sufficient —
// no TTL needed. Invalidation clears both tiers. Version-pinned lookups are never persisted to
// localStorage because they are immutable snapshots rather than "latest" live state.

const viewCache = new Map<string, WorkflowView>()
const inFlight = new Map<string, Promise<WorkflowView>>()

function viewLsKey(projectId: string, slug: string): string {
  return `wfv_${projectId}::${slug}`
}

function cacheKey(projectId: string, slug: string, version?: number): string {
  return `${projectId}::${slug}::${version ?? 'latest'}`
}

/**
 * Synchronously read a cached view. For 'latest' lookups, pre-seeds the module cache from
 * localStorage so the first render after a page refresh also gets a synchronous hit.
 */
export function getCachedWorkflowView(
  projectId: string,
  slug: string,
  version?: number,
): WorkflowView | undefined {
  const key = cacheKey(projectId, slug, version)
  if (!viewCache.has(key) && version == null) {
    try {
      const raw = localStorage.getItem(viewLsKey(projectId, slug))
      if (raw) viewCache.set(key, JSON.parse(raw) as WorkflowView)
    } catch { /* */ }
  }
  return viewCache.get(key)
}

/**
 * Fetch (and cache) a WorkflowView by slug. Concurrent callers for the same slug share one request,
 * so the issues table's many StatusDropdowns trigger a single network call.
 */
export function fetchWorkflowView(
  projectId: string,
  slug: string,
  token: string,
  version?: number,
): Promise<WorkflowView> {
  const key = cacheKey(projectId, slug, version)
  const cached = viewCache.get(key)
  if (cached) return Promise.resolve(cached)

  const pending = inFlight.get(key)
  if (pending) return pending

  const qs = version != null ? `?version=${version}` : ''
  const promise = apiGet<WorkflowView>(
    `/api/v1/projects/${projectId}/workflows/by-slug/${slug}${qs}`,
    token,
  )
    .then((view) => {
      viewCache.set(key, view)
      // Persist the 'latest' view to localStorage so getCachedWorkflowView() can pre-seed
      // synchronously on the next page load (eliminates the status-label flash on refresh).
      if (version == null) {
        try { localStorage.setItem(viewLsKey(projectId, slug), JSON.stringify(view)) } catch { /* */ }
      }
      return view
    })
    .finally(() => {
      inFlight.delete(key)
    })

  inFlight.set(key, promise)
  return promise
}

/** Drop a cached view so the next read re-fetches (e.g. after publishing a new version). */
export function invalidateWorkflowView(projectId: string, slug: string, version?: number): void {
  viewCache.delete(cacheKey(projectId, slug, version))
  if (version == null) {
    try { localStorage.removeItem(viewLsKey(projectId, slug)) } catch { /* */ }
  }
}

/** React hook: resolve one Workflow's view by slug, sharing the module cache. */
export function useWorkflowView(
  projectId: string | undefined,
  slug: string | undefined,
  token: string | null | undefined,
  version?: number,
): WorkflowView | undefined {
  const [view, setView] = useState<WorkflowView | undefined>(() =>
    projectId && slug ? getCachedWorkflowView(projectId, slug, version) : undefined,
  )

  useEffect(() => {
    if (!projectId || !slug || !token) return
    let cancelled = false
    // fetchWorkflowView resolves synchronously from the cache when present, so the cached case still
    // updates without a separate synchronous setState in the effect body (the initializer above
    // already seeds the first render from the cache, avoiding a flash).
    fetchWorkflowView(projectId, slug, token, version)
      .then((v) => {
        if (!cancelled) setView(v)
      })
      .catch(() => {
        /* non-fatal — helpers fall back to humanized ids */
      })
    return () => {
      cancelled = true
    }
  }, [projectId, slug, token, version])

  return view
}

/** React hook: resolve several Workflows' views at once (issues list spans whatever slugs are present). */
export function useWorkflowViews(
  projectId: string | undefined,
  slugs: string[],
  token: string | null | undefined,
): Record<string, WorkflowView> {
  // Stable key so the effect only re-runs when the *set* of slugs changes.
  const slugKey = [...new Set(slugs)].sort().join(',')
  const [views, setViews] = useState<Record<string, WorkflowView>>({})

  useEffect(() => {
    if (!projectId || !token || !slugKey) return
    let cancelled = false
    const distinct = slugKey.split(',')
    Promise.all(
      distinct.map((slug) =>
        fetchWorkflowView(projectId, slug, token)
          .then((v) => [slug, v] as const)
          .catch(() => null),
      ),
    ).then((entries) => {
      if (cancelled) return
      const next: Record<string, WorkflowView> = {}
      for (const entry of entries) if (entry) next[entry[0]] = entry[1]
      setViews(next)
    })
    return () => {
      cancelled = true
    }
  }, [projectId, token, slugKey])

  return views
}

// ── Render helpers (the single source of label + color) ─────────────────────

/** Title-case an UPPER_SNAKE id, e.g. READY_FOR_DEVELOPMENT → "Ready For Development". */
export function humanizeId(id: string): string {
  return id
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

/** Pluralize a Workflow noun for page titles and nav labels, e.g. "Issue" → "Issues", "Story" → "Stories". */
export function pluralizeNoun(noun: string): string {
  return pluralize(noun)
}

/**
 * Authoritative lifecycle (statechart) vs automation (YAML) check — reads the server-derived `kind`,
 * never the shape of `definition`. (COND-22)
 */
export function isLifecycleWorkflow(wf: WorkflowDefinitionDto): boolean {
  return wf.kind === 'LIFECYCLE'
}

/**
 * Published lifecycle workflows flagged for the sidebar nav (COND-22). Keeps the list query contract
 * co-located here rather than hand-built at the call site.
 */
export function listSidebarWorkflows(projectId: string, token: string): Promise<WorkflowDefinitionDto[]> {
  return apiGet<WorkflowDefinitionDto[]>(
    `/api/v1/projects/${projectId}/workflows?lifecycle=true&state=PUBLISHED&sidebar=true`,
    token,
  )
}

// ── Area/Noun URL routing (the workflow-scoped URL shape) ───────────────────
//
// The Work Item URL is two human-readable segments — `area`/pluralized-`noun` (both lowercased) — plus
// the Work Item's displayId. The segments are display metadata, NOT the API slug: slug resolution is
// case-sensitive server-side, so callers resolve a segment pair back to the REAL UPPER_SNAKE slug here
// (from the sidebar workflows list) before touching any API. Builders centralize the URL shape.

/** `/app/projects/{id}/{area}/{nouns}` — the workflow-scoped Work Item list URL. Both segments lowercased. */
export function workItemListPath(projectId: string, area: string, noun: string): string {
  return `/app/projects/${projectId}/${area.toLowerCase()}/${pluralizeNoun(noun).toLowerCase()}`
}

/** `/app/projects/{id}/{area}/{nouns}/{displayId}` — the workflow-scoped Work Item detail URL. */
export function workItemDetailPath(
  projectId: string,
  area: string,
  noun: string,
  displayId: string,
): string {
  return `${workItemListPath(projectId, area, noun)}/${displayId}`
}

// The sidebar workflows list, cached at module scope (workflows change rarely; the area/noun routes and
// the redirect shims all read it). Concurrent callers share one in-flight request, mirroring the
// WorkflowView cache above.
//
// Persistence layer: the resolved list is also written to localStorage so that on page refresh the
// sidebar nav can hydrate synchronously before any network call completes (stale-while-revalidate).
const sidebarListCache = new Map<string, WorkflowDefinitionDto[]>()
const sidebarListInFlight = new Map<string, Promise<WorkflowDefinitionDto[]>>()

function sidebarLsKey(projectId: string): string {
  return `sidebar_workflows_${projectId}`
}

function loadSidebarFromStorage(projectId: string): WorkflowDefinitionDto[] | null {
  try {
    const raw = localStorage.getItem(sidebarLsKey(projectId))
    return raw ? (JSON.parse(raw) as WorkflowDefinitionDto[]) : null
  } catch {
    return null
  }
}

function saveSidebarToStorage(projectId: string, list: WorkflowDefinitionDto[]): void {
  try {
    localStorage.setItem(sidebarLsKey(projectId), JSON.stringify(list))
  } catch {
    // localStorage may be full or unavailable; non-fatal
  }
}

/**
 * Fetch (and cache) the sidebar workflow list for a project. Always makes a network request —
 * concurrent callers share one in-flight Promise so there is exactly one request per project at
 * a time. When resolved, the result is written to the module cache and localStorage so that
 * {@link getSidebarCacheEntry} can serve synchronous initial renders on the next mount.
 */
export function fetchSidebarWorkflows(
  projectId: string,
  token: string,
): Promise<WorkflowDefinitionDto[]> {
  const pending = sidebarListInFlight.get(projectId)
  if (pending) return pending

  const promise = listSidebarWorkflows(projectId, token)
    .then((list) => {
      sidebarListCache.set(projectId, list)
      saveSidebarToStorage(projectId, list)
      return list
    })
    .finally(() => {
      sidebarListInFlight.delete(projectId)
    })
  sidebarListInFlight.set(projectId, promise)
  return promise
}

/**
 * Synchronously read the cached sidebar workflow list, pre-seeding from localStorage if the
 * module cache is cold. Use this for the `useState` initializer to render nav items immediately
 * on revisit — before any network call completes.
 */
export function getSidebarCacheEntry(projectId: string): WorkflowDefinitionDto[] | undefined {
  if (!sidebarListCache.has(projectId)) {
    const stored = loadSidebarFromStorage(projectId)
    if (stored) sidebarListCache.set(projectId, stored)
  }
  return sidebarListCache.get(projectId)
}

/** Invalidate the sidebar cache for a project (call after publishing or deleting a workflow). */
export function invalidateSidebarCache(projectId: string): void {
  sidebarListCache.delete(projectId)
  sidebarListInFlight.delete(projectId)
  try { localStorage.removeItem(sidebarLsKey(projectId)) } catch { /* */ }
}

/** Clear all sidebar caches including localStorage (used in tests to prevent cross-test contamination). */
export function clearAllSidebarCaches(): void {
  sidebarListCache.clear()
  sidebarListInFlight.clear()
  try {
    const keysToRemove: string[] = []
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k?.startsWith('sidebar_workflows_')) keysToRemove.push(k)
    }
    keysToRemove.forEach((k) => localStorage.removeItem(k))
  } catch { /* */ }
}

/**
 * Resolve an `area`/`noun` URL segment pair back to its Workflow. The match is case-insensitive and
 * noun-pluralized, mirroring how {@link workItemListPath} builds the URL. Returns `undefined` when no
 * sidebar Workflow matches. The caller then uses the resolved Workflow's REAL slug for API calls.
 */
export async function resolveWorkflowByAreaNoun(
  projectId: string,
  areaSeg: string,
  nounSeg: string,
  token: string,
): Promise<WorkflowDefinitionDto | undefined> {
  const list = await fetchSidebarWorkflows(projectId, token)
  const area = areaSeg.toLowerCase()
  const noun = nounSeg.toLowerCase()
  return list.find(
    (wf) =>
      wf.area?.toLowerCase() === area &&
      pluralizeNoun(wf.noun ?? wf.name).toLowerCase() === noun,
  )
}

/** Resolution status for {@link useWorkflowByAreaNoun}. */
export type AreaNounResolution = {
  status: 'loading' | 'ready' | 'notfound'
  workflow?: WorkflowDefinitionDto
}

/** React hook: resolve a Workflow from `area`/`noun` URL segments, sharing the module cache. */
export function useWorkflowByAreaNoun(
  projectId: string | undefined,
  area: string | undefined,
  noun: string | undefined,
  token: string | null | undefined,
): AreaNounResolution {
  // Synchronously resolve from the module cache (warmed by fetchSidebarWorkflows or localStorage
  // pre-seed) so the page skips the loading state on revisit and client-side navigation.
  const [resolution, setResolution] = useState<AreaNounResolution>(() => {
    if (!projectId || !area || !noun) return { status: 'loading' }
    const cached = sidebarListCache.get(projectId)
    if (!cached) return { status: 'loading' }
    const wf = cached.find(
      (w) =>
        w.area?.toLowerCase() === area.toLowerCase() &&
        pluralizeNoun(w.noun ?? w.name).toLowerCase() === noun.toLowerCase(),
    )
    return wf ? { status: 'ready', workflow: wf } : { status: 'notfound' }
  })

  useEffect(() => {
    if (!projectId || !area || !noun || !token) return
    let cancelled = false
    // Only show loading state when we have no data at all (cold start, no localStorage).
    if (resolution.status === 'loading') {
      // status stays 'loading' — we'll update below
    }
    resolveWorkflowByAreaNoun(projectId, area, noun, token)
      .then((wf) => {
        if (cancelled) return
        setResolution(wf ? { status: 'ready', workflow: wf } : { status: 'notfound' })
      })
      .catch(() => {
        if (!cancelled) setResolution({ status: 'notfound' })
      })
    return () => {
      cancelled = true
    }
  }, [projectId, area, noun, token]) // eslint-disable-line react-hooks/exhaustive-deps

  return resolution
}

/** Resolve a status id to its display label + category, falling back gracefully when unloaded. */
export function statusMeta(
  view: WorkflowView | undefined,
  statusId: string,
): { label: string; category: string } {
  const status = view?.statuses.find((s) => s.id === statusId)
  return {
    label: status?.label ?? humanizeId(statusId),
    category: status?.category ?? 'open',
  }
}

type StatusBadgeVariant = NonNullable<BadgeProps['variant']>

/**
 * The one category → color mapping. open → neutral/grey, in_progress → blue, terminal → green.
 * Returns a Badge variant; {@link categoryColor} returns the same palette as raw classes for the
 * non-Badge diagram nodes.
 */
export function categoryVariant(category: string): StatusBadgeVariant {
  if (category === 'in_progress') return 'status-review' // blue
  if (category === 'terminal') return 'status-done' // green
  return 'status-draft' // open + unknown → neutral grey
}

/** Raw tailwind classes for the same palette, for contexts that don't use <Badge> (e.g. the diagram). */
export function categoryColor(category: string): string {
  if (category === 'in_progress') return 'bg-status-review/10 text-status-review border-status-review/30'
  if (category === 'terminal') return 'bg-status-done/10 text-status-done border-status-done/30'
  return 'bg-status-draft/10 text-status-draft border-status-draft/30'
}

/** True when the given status has an outgoing transition that is review-gated (drives review panels). */
export function statusHasReviewGate(view: WorkflowView | undefined, statusId: string): boolean {
  return !!view?.transitions.some((t) => t.from === statusId && t.requiresReview)
}

/** The review-gated transition out of a status, if any (carries reviewOutcomes when present). */
export function reviewGateForStatus(view: WorkflowView | undefined, statusId: string) {
  return view?.transitions.find((t) => t.from === statusId && t.requiresReview)
}

/** Statuses in a category bucket. Active = open + in_progress; Done = terminal. */
export function categoriesForView(view: 'active' | 'done' | 'all'): WorkflowStatusCategory[] {
  if (view === 'active') return ['open', 'in_progress']
  if (view === 'done') return ['terminal']
  return ['open', 'in_progress', 'terminal']
}

// ── Lifecycle (statechart) Workflow CRUD ────────────────────────────────────

/** List the published version history of a Workflow, newest first, with active version + work-item counts. */
export function listWorkflowVersions(
  projectId: string,
  workflowId: string,
  token: string,
): Promise<WorkflowVersionsResponse> {
  return apiGet<WorkflowVersionsResponse>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}/versions`,
    token,
  )
}

/** Disable a PUBLISHED lifecycle Workflow — existing Work Items keep their version, no new bindings. */
export function disableWorkflow(
  projectId: string,
  workflowId: string,
  token: string,
): Promise<WorkflowDefinitionDto> {
  return apiPost<WorkflowDefinitionDto>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}/disable`,
    {},
    token,
  )
}

/** Re-enable a DISABLED lifecycle Workflow so new Work Items can bind to it again. */
export function enableWorkflow(
  projectId: string,
  workflowId: string,
  token: string,
): Promise<WorkflowDefinitionDto> {
  return apiPost<WorkflowDefinitionDto>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}/enable`,
    {},
    token,
  )
}

/** Create a lifecycle (statechart) Workflow. Returns the saved workflow + any validation warnings. */
export function createLifecycleWorkflow(
  projectId: string,
  body: { name: string; area?: string; definition: StatechartDefinition },
  token: string,
): Promise<WorkflowCreateResponse> {
  return apiPost<WorkflowCreateResponse>(`/api/v1/projects/${projectId}/workflows`, body, token)
}

/** Update a lifecycle Workflow's definition (re-validated server-side). */
export function updateLifecycleWorkflow(
  projectId: string,
  workflowId: string,
  body: { name?: string; area?: string; definition: StatechartDefinition },
  token: string,
): Promise<WorkflowCreateResponse> {
  return apiPut<WorkflowCreateResponse>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}`,
    body,
    token,
  )
}

/** Promote a DRAFT Workflow to PUBLISHED (403 if not ADMIN/CREATOR, 422 if invalid). */
export function publishWorkflow(
  projectId: string,
  workflowId: string,
  token: string,
): Promise<WorkflowDefinitionDto> {
  return apiPost<WorkflowDefinitionDto>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}/publish`,
    {},
    token,
  )
}

// ── Project members cache ────────────────────────────────────────────────────
//
// PermissionsContext (mounted at project layout level) and WorkItemListView both call
// GET /members on every work-item page mount — two identical concurrent requests. Sharing
// a module-scope cache with in-flight deduplication collapses them to one. The result is
// also written to localStorage so the member list (and therefore userRole resolution) is
// available synchronously on the next page refresh.

const membersCache = new Map<string, Member[]>()
const membersInFlight = new Map<string, Promise<Member[]>>()

function membersLsKey(projectId: string): string {
  return `members_${projectId}`
}

/** Load members synchronously from the two-tier cache (module → localStorage). */
export function getMembersCacheEntry(projectId: string): Member[] | undefined {
  if (!membersCache.has(projectId)) {
    try {
      const raw = localStorage.getItem(membersLsKey(projectId))
      if (raw) membersCache.set(projectId, JSON.parse(raw) as Member[])
    } catch { /* */ }
  }
  return membersCache.get(projectId)
}

/**
 * Fetch the project member list, deduplicating concurrent callers.
 * Both PermissionsContext and WorkItemListView share the same in-flight Promise so only one
 * network request fires regardless of component mount order.
 */
export function fetchMembersCached(projectId: string, token: string): Promise<Member[]> {
  const cached = membersCache.get(projectId)
  if (cached) return Promise.resolve(cached)
  const pending = membersInFlight.get(projectId)
  if (pending) return pending
  const promise = apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, token)
    .then((list) => {
      membersCache.set(projectId, list)
      try { localStorage.setItem(membersLsKey(projectId), JSON.stringify(list)) } catch { /* */ }
      return list
    })
    .finally(() => { membersInFlight.delete(projectId) })
  membersInFlight.set(projectId, promise)
  return promise
}

/** Clear cached members so the next call re-fetches (e.g. after a role change or member removal). */
export function invalidateMembersCache(projectId: string): void {
  membersCache.delete(projectId)
  membersInFlight.delete(projectId)
  try { localStorage.removeItem(membersLsKey(projectId)) } catch { /* */ }
}
