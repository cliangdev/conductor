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
import { parseWorkflowYaml, isManualTrigger, type TriggerKind } from '@/lib/workflowAutomation'
import { triggerLabel } from '@/components/workflow/TriggerBadges'
import type {
  WorkflowView,
  WorkflowStatusCategory,
  WorkflowVersionsResponse,
} from '@/types/workItem'
import type {
  WorkflowCreateResponse,
  WorkflowDefinitionDto,
  WorkflowRunDto,
  WorkflowScheduleSkipDto,
} from '@/types/workflow'
import type { StatechartDefinition } from '@/lib/workflowDefinition'
import type { Member } from '@/types'

/** The project's default Workflow. Work Items with no explicit slug resolve to this. */
export const DEFAULT_WORKFLOW_SLUG = 'ENGINEERING'

/** All Workflows (lifecycle + automation) for a project — unfiltered, uncached. */
export function listWorkflows(projectId: string, token: string): Promise<WorkflowDefinitionDto[]> {
  return apiGet<WorkflowDefinitionDto[]>(`/api/v1/projects/${projectId}/workflows`, token)
}

/** Manually trigger a workflow run. `inputs` are exposed to steps as `${{ inputs.KEY }}`. */
export function dispatchWorkflow(
  projectId: string,
  workflowId: string,
  inputs: Record<string, string> | undefined,
  token: string,
): Promise<WorkflowRunDto> {
  return apiPost<WorkflowRunDto>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}/dispatch`,
    inputs ? { inputs } : {},
    token,
  )
}

/** Request cancellation of a PENDING/RUNNING run. Idempotent while CANCELLING; 409 once terminal. */
export function cancelWorkflowRun(
  projectId: string,
  workflowId: string,
  runId: string,
  token: string,
): Promise<WorkflowRunDto> {
  return apiPost<WorkflowRunDto>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}/runs/${runId}/cancel`,
    {},
    token,
  )
}

export interface CancelQueuedRunsResponse {
  cancelledCount: number
}

/**
 * Cancels every PENDING run for the workflow, plus any run that reads RUNNING but is only blocked on
 * a self-hosted job no daemon has claimed yet. Never touches a run with genuinely in-flight work
 * (a RUNNING job, or an already-claimed AWAITING_PICKUP job) — a subset of what the UI displays as
 * "Queued", not the full displayed set.
 */
export function cancelQueuedWorkflowRuns(
  projectId: string,
  workflowId: string,
  token: string,
): Promise<CancelQueuedRunsResponse> {
  return apiPost<CancelQueuedRunsResponse>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}/runs/cancel-queued`,
    {},
    token,
  )
}

/** Recent cron ticks dropped because a `concurrency: single` run was already in flight, newest first. */
export function listScheduleSkips(
  projectId: string,
  workflowId: string,
  token: string,
  limit?: number,
): Promise<WorkflowScheduleSkipDto[]> {
  const qs = limit != null ? `?limit=${limit}` : ''
  return apiGet<WorkflowScheduleSkipDto[]>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}/schedule-skips${qs}`,
    token,
  )
}

/** Runs for one Workflow, newest first. `status` repeats the raw-status query param (backend OR's
 *  them); `state` is the derived UI-facing alternative ("queued"/"running" — see the backend's
 *  `?state=` contract in openapi.yaml). The two are mutually exclusive server-side (400 if both are
 *  sent), so this throws client-side rather than letting a caller trip that 400 at request time. */
export function listWorkflowRuns(
  projectId: string,
  workflowId: string,
  token: string,
  opts?: { page?: number; size?: number; status?: readonly string[]; state?: 'queued' | 'running' },
): Promise<WorkflowRunDto[]> {
  if (opts?.state && opts.status?.length) {
    throw new Error('listWorkflowRuns: `state` and `status` are mutually exclusive')
  }
  const page = opts?.page ?? 0
  const size = opts?.size ?? 20
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  for (const status of opts?.status ?? []) params.append('status', status)
  if (opts?.state) params.set('state', opts.state)
  return apiGet<WorkflowRunDto[]>(
    `/api/v1/projects/${projectId}/workflows/${workflowId}/runs?${params.toString()}`,
    token,
  )
}

// Maps a WorkflowRunDto.triggerType (the raw id WorkflowTriggerService stores on the run) to the same
// TriggerKind vocabulary parseWorkflowYaml uses for the YAML "on:" block — the "conductor."/"github."
// prefixed ids below are literally the raw YAML keys it already knows how to read.
const RUN_TRIGGER_KIND: Record<string, TriggerKind> = {
  workflow_dispatch: 'workflow_dispatch',
  webhook: 'webhook',
  schedule: 'schedule',
  'conductor.work_item.status_changed': 'work_item_status_changed',
  'github.pull_request': 'github_pull_request',
}

/**
 * Human label for a run's raw `triggerType` (e.g. "conductor.work_item.status_changed" → "work item"),
 * reusing TriggerBadges' triggerLabel/TRIGGER_LABEL map rather than a second copy. A `workflow_dispatch`
 * run always reads as "manual" here — a run has no access to the YAML's `manual: false` opt-out, so a
 * system-triggered dispatch (e.g. the knowledge-librarian) still reads as "manual".
 */
export function humanizeTriggerType(triggerType: string): string {
  const kind = RUN_TRIGGER_KIND[triggerType]
  if (!kind) return humanizeId(triggerType.replace(/\./g, '_'))
  return triggerLabel({ kind, raw: {} })
}

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

// ── Status hue resolution (domain knowledge: which status/category maps to which color) ────

export type StatusHue = 'gray' | 'blue' | 'amber' | 'violet' | 'teal' | 'green' | 'slate' | 'red'

// Well-known status ids, normalized (lowercased, non-alphanumerics stripped) before lookup
// so "in_review", "in-review", "IN REVIEW" all resolve the same way.
const WELL_KNOWN_HUES: Record<string, StatusHue> = {
  draft: 'gray',
  backlog: 'gray',
  pending: 'gray',
  inreview: 'blue',
  review: 'blue',
  running: 'blue',
  inprogress: 'amber',
  codereview: 'violet',
  approved: 'teal',
  done: 'green',
  succeeded: 'green',
  success: 'green',
  closed: 'slate',
  skipped: 'slate',
  cancelling: 'slate',
  cancelled: 'slate',
  failed: 'red',
  error: 'red',
  loopexhausted: 'amber',
  // Self-hosted-runner job/run states (WorkflowJobStatus.AWAITING_PICKUP / WorkflowRunStatus.LOCAL_PICKUP_TIMEOUT).
  // Amber (not blue/"running") because the job hasn't started — it's queued, waiting on a daemon to
  // claim it, the same "not yet active" meaning amber carries for in-progress/attention states.
  awaitingpickup: 'amber',
  // Red (not slate/"skipped") — WorkflowRunStatus.java's own javadoc calls a LOCAL_PICKUP_TIMEOUT
  // run "effectively dead" (the self-hosted daemon never claimed it); it never produced a result,
  // so it reads as a failure, not a benign skip.
  localpickuptimeout: 'red',
}

// Human labels for status ids whose raw form reads as schema jargon rather than something a user
// would say out loud — the design system's "translate at the UI boundary" rule. Everything else
// falls back to humanizeId, which is legible enough on its own (e.g. "Loop Exhausted").
const WELL_KNOWN_LABELS: Record<string, string> = {
  awaitingpickup: 'Waiting for runner',
  localpickuptimeout: 'Never picked up',
}

// Fallback when the status id itself isn't recognized — keyed by workflow/lifecycle category.
const CATEGORY_HUES: Record<string, StatusHue> = {
  open: 'gray',
  inprogress: 'amber',
  terminal: 'green',
}

function normalizeStatusId(value: string): string {
  return value.toLowerCase().replace(/[^a-z0-9]/g, '')
}

/** Resolve a status (and optional fallback category) to one of the 8 canonical status hues. */
export function statusHue(status: string, category?: string): StatusHue {
  const known = WELL_KNOWN_HUES[normalizeStatusId(status)]
  if (known) return known
  return categoryHue(category)
}

/** Resolve a category bucket (open/in_progress/terminal) to its hue, defaulting to gray. */
export function categoryHue(category?: string): StatusHue {
  const fromCategory = category ? CATEGORY_HUES[normalizeStatusId(category)] : undefined
  return fromCategory ?? 'gray'
}

/** Human label for a status id — the default `StatusBadge` label when no explicit `label` prop is
 *  given. Looks up {@link WELL_KNOWN_LABELS} first, then falls back to {@link humanizeId}. */
export function statusLabel(status: string): string {
  return WELL_KNOWN_LABELS[normalizeStatusId(status)] ?? humanizeId(status)
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
 * Whether the Run button / dispatch endpoint should be offered for this workflow's YAML — mirrors
 * the backend's `TriggersSpec#allowsManualDispatch` (WorkflowController rejects the dispatch either
 * way; this just keeps the button from being shown for a click that can only fail). False when there's
 * no `workflow_dispatch:` trigger at all, or when it opts out with `manual: false` — used by
 * system-managed workflows like knowledge-librarian whose event payload is built by the process that
 * dispatches them, not by a human clicking Run.
 *
 * Deliberately the opposite default from the backend's private `allowsManualDispatch` in
 * WorkflowController (true for null yaml) — currently unreachable either way since a null/empty yaml
 * only happens for a lifecycle workflow, which never renders a Run button in the first place. Don't
 * "fix" one side to match the other without re-checking that still holds.
 */
export function allowsManualDispatch(yaml: string | null | undefined): boolean {
  if (!yaml) return false
  try {
    const { triggers } = parseWorkflowYaml(yaml)
    const dispatch = triggers.find(t => t.kind === 'workflow_dispatch')
    return dispatch ? isManualTrigger(dispatch) : false
  } catch {
    return true
  }
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

/** A sidebar nav entry derived from a sidebar-enabled lifecycle Workflow. */
export interface WorkNavEntry {
  slug: string
  label: string
  noun: string
  area: string
  createdAt: string
}

/**
 * Map sidebar-enabled lifecycle Workflows to nav entries, stably ordered by creation time. Reads the
 * first-class `slug`/`noun`/`area` fields the server now exposes — never the raw statechart `definition`.
 */
export function toWorkNav(workflows: WorkflowDefinitionDto[]): WorkNavEntry[] {
  return workflows
    .filter(isLifecycleWorkflow)
    .map((wf) => ({
      slug: wf.slug ?? wf.name,
      label: pluralizeNoun(wf.noun ?? wf.name),
      noun: wf.noun ?? wf.name,
      area: wf.area ?? 'WORK',
      createdAt: wf.createdAt,
    }))
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
}

/** Group nav entries by area slug, preserving first-seen (creation) order of areas and entries. */
export function groupByArea(entries: WorkNavEntry[]): [string, WorkNavEntry[]][] {
  const groups = new Map<string, WorkNavEntry[]>()
  for (const e of entries) {
    const list = groups.get(e.area) ?? []
    list.push(e)
    groups.set(e.area, list)
  }
  return [...groups.entries()]
}

/**
 * React hook: the dynamic Work nav — one entry per sidebar-enabled, published lifecycle Workflow for
 * a project. Shared by the Sidebar and the CommandPalette so both read the exact same list. Hydrates
 * synchronously from the module cache (pre-seeded from localStorage) so nav appears instantly on
 * revisit, then revalidates in the background via {@link fetchSidebarWorkflows}.
 */
export function useSidebarWorkNav(
  projectId: string | undefined,
  token: string | null | undefined,
): { entries: WorkNavEntry[]; loading: boolean } {
  const [entries, setEntries] = useState<WorkNavEntry[]>(() => {
    if (!projectId) return []
    const cached = getSidebarCacheEntry(projectId)
    return cached ? toWorkNav(cached) : []
  })
  const [loading, setLoading] = useState(entries.length === 0)

  useEffect(() => {
    if (!projectId || !token) return
    let cancelled = false
    fetchSidebarWorkflows(projectId, token)
      .then((wfs) => {
        if (!cancelled) {
          setEntries(toWorkNav(wfs))
          setLoading(false)
        }
      })
      .catch(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [projectId, token])

  return { entries, loading }
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
