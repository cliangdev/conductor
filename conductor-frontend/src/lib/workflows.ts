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
  WorkflowVersionSummary,
} from '@/types/workItem'
import type {
  WorkflowCreateResponse,
  WorkflowDefinitionDto,
} from '@/types/workflow'
import type { StatechartDefinition } from '@/lib/workflowDefinition'

/** The project's default Workflow. Work Items with no explicit slug resolve to this. */
export const DEFAULT_WORKFLOW_SLUG = 'ENGINEERING'

// ── WorkflowView cache ──────────────────────────────────────────────────────

const viewCache = new Map<string, WorkflowView>()
const inFlight = new Map<string, Promise<WorkflowView>>()

function cacheKey(projectId: string, slug: string, version?: number): string {
  return `${projectId}::${slug}::${version ?? 'latest'}`
}

/** Synchronously read a cached view, if one has already been fetched. */
export function getCachedWorkflowView(
  projectId: string,
  slug: string,
  version?: number,
): WorkflowView | undefined {
  return viewCache.get(cacheKey(projectId, slug, version))
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

/** List the published version history of a Workflow, newest first. */
export function listWorkflowVersions(
  projectId: string,
  workflowId: string,
  token: string,
): Promise<WorkflowVersionSummary[]> {
  return apiGet<WorkflowVersionSummary[]>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}/versions`,
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
