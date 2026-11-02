// Delivery tracking reads and writes (docs/prd-tracking.md): the tree under a PRD, dependencies,
// criteria, milestones. One copy of each call so the Plan tab, properties panel and roadmap agree on
// paths and shapes.

import { apiDelete, apiGet, apiPatch, apiPost, apiPut } from './api'
import { parseTargetOn } from './roadmap'
import type {
  DeliveryOverview,
  DependencyGraph,
  DependencyKind,
  ForecastFit,
  HealthStatus,
  HealthUpdate,
  Milestone,
  MilestoneState,
  WorkItemCriterion,
  WorkItemDependencies,
  WorkItemForecast,
  WorkItemPriority,
  WorkItemTreeNode,
} from '@/types/workItem'

const items = (projectId: string) => `/api/v2/projects/${projectId}/work-items`
const milestones = (projectId: string) => `/api/v2/projects/${projectId}/milestones`

export const PRIORITIES: WorkItemPriority[] = ['P0', 'P1', 'P2', 'P3']

/** What a priority chip says; "No priority" for an item without one. */
export function priorityLabel(priority: WorkItemPriority | null | undefined): string {
  switch (priority) {
    case 'P0':
      return 'P0 · Urgent'
    case 'P1':
      return 'P1 · High'
    case 'P2':
      return 'P2 · Medium'
    case 'P3':
      return 'P3 · Low'
    default:
      return 'No priority'
  }
}

export function fetchTree(projectId: string, workItemId: string, token: string): Promise<WorkItemTreeNode> {
  return apiGet<WorkItemTreeNode>(`${items(projectId)}/${workItemId}/tree`, token)
}

export function fetchDependencyGraph(projectId: string, workItemId: string, token: string): Promise<DependencyGraph> {
  return apiGet<DependencyGraph>(`${items(projectId)}/${workItemId}/dependency-graph`, token)
}

export function fetchDependencies(projectId: string, workItemId: string, token: string): Promise<WorkItemDependencies> {
  return apiGet<WorkItemDependencies>(`${items(projectId)}/${workItemId}/dependencies`, token)
}

/** Asserts "`workItemId` *kind* `relatedWorkItemId`" (A BLOCKS B); answers the grouped listing. */
export function addDependency(
  projectId: string,
  workItemId: string,
  kind: DependencyKind,
  relatedWorkItemId: string,
  token: string
): Promise<WorkItemDependencies> {
  return apiPost<WorkItemDependencies>(`${items(projectId)}/${workItemId}/dependencies`, { kind, relatedWorkItemId }, token)
}

export function removeDependency(projectId: string, workItemId: string, dependencyId: string, token: string): Promise<void> {
  return apiDelete(`${items(projectId)}/${workItemId}/dependencies/${dependencyId}`, token)
}

export function fetchCriteria(projectId: string, workItemId: string, token: string): Promise<WorkItemCriterion[]> {
  return apiGet<WorkItemCriterion[]>(`${items(projectId)}/${workItemId}/criteria`, token)
}

export interface PatchCriterionResult {
  criterion: WorkItemCriterion
  criteriaMet: number
  criteriaTotal: number
}

export function patchCriterion(
  projectId: string,
  workItemId: string,
  criterionId: string,
  patch: { text?: string; kind?: 'auto' | 'manual'; met?: boolean; evidence?: string | null },
  token: string
): Promise<PatchCriterionResult | undefined> {
  return apiPatch<PatchCriterionResult>(`${items(projectId)}/${workItemId}/criteria/${criterionId}`, patch, token)
}

export function fetchMilestones(projectId: string, token: string): Promise<Milestone[]> {
  return apiGet<Milestone[]>(milestones(projectId), token)
}

export interface MilestoneInput {
  name?: string
  description?: string | null
  targetOn?: string | null
  state?: MilestoneState
}

export function createMilestone(projectId: string, input: MilestoneInput & { name: string }, token: string): Promise<Milestone> {
  return apiPost<Milestone>(milestones(projectId), input, token)
}

export function patchMilestone(projectId: string, milestoneId: string, input: MilestoneInput, token: string): Promise<Milestone | undefined> {
  return apiPatch<Milestone>(`${milestones(projectId)}/${milestoneId}`, input, token)
}

export function deleteMilestone(projectId: string, milestoneId: string, token: string): Promise<void> {
  return apiDelete(`${milestones(projectId)}/${milestoneId}`, token)
}

/** Delivery fields the properties panel saves; `null` clears, matching how `dueOn` is cleared. */
export interface DeliveryPatch {
  parentWorkItemId?: string | null
  priority?: WorkItemPriority | null
  milestoneId?: string | null
  appetite?: string | null
}

export function patchDelivery<T>(projectId: string, workItemId: string, patch: DeliveryPatch, token: string): Promise<T | undefined> {
  const body: Record<string, string> = {}
  for (const [key, value] of Object.entries(patch)) {
    if (value !== undefined) body[key] = value ?? ''
  }
  return apiPatch<T>(`${items(projectId)}/${workItemId}`, body, token)
}

export function fetchForecast(projectId: string, workItemId: string, token: string): Promise<WorkItemForecast> {
  return apiGet<WorkItemForecast>(`${items(projectId)}/${workItemId}/forecast`, token)
}

export function fetchDeliveryOverview(projectId: string, token: string): Promise<DeliveryOverview> {
  return apiGet<DeliveryOverview>(`/api/v2/projects/${projectId}/delivery-overview`, token)
}

/** `draft: false` records a confirmed update (a person only); `true` leaves a pending draft. */
export function createHealthUpdate(
  projectId: string,
  workItemId: string,
  input: { health: HealthStatus; note: string; draft: boolean },
  token: string
): Promise<HealthUpdate> {
  return apiPost<HealthUpdate>(`${items(projectId)}/${workItemId}/health-updates`, input, token)
}

/** Confirms a pending draft, optionally editing its health or note first. */
export function confirmHealthUpdate(
  projectId: string,
  workItemId: string,
  updateId: string,
  edits: { health?: HealthStatus; note?: string },
  token: string
): Promise<HealthUpdate> {
  return apiPost<HealthUpdate>(`${items(projectId)}/${workItemId}/health-updates/${updateId}/confirm`, edits, token)
}

/** "Nov 12" for a `YYYY-MM-DD` date, read as a local day so it never slips across time zones. */
export function formatForecastDate(date: string): string {
  const ms = parseTargetOn(date)
  return ms === null ? date : new Date(ms).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

/**
 * One line for a forecast: "P50 Nov 12 · P85 Nov 20 · fits appetite · fits the milestone". `formatDate`
 * renders a `YYYY-MM-DD` string (injected so tests and locales stay in the caller's hands).
 * `milestoneTargetOn` lets a missed milestone say by how many days.
 */
export function forecastLine(
  forecast: WorkItemForecast,
  formatDate: (date: string) => string,
  milestoneTargetOn?: string | null
): string {
  switch (forecast.state) {
    case 'DONE':
      return 'Done'
    case 'NO_PLAN':
      return 'No plan yet'
    case 'INSUFFICIENT_DATA': {
      const needed = forecast.needed ?? 8
      const more = Math.max(needed - forecast.samples, 0)
      return `Needs ${needed} completed tasks (${forecast.samples} so far)${more > 0 ? `, ${more} more` : ''}`
    }
    case 'OK': {
      const parts: string[] = []
      if (forecast.p50Date) parts.push(`P50 ${formatDate(forecast.p50Date)}`)
      if (forecast.p85Date) parts.push(`P85 ${formatDate(forecast.p85Date)}`)
      const appetite = fitText(forecast.appetiteFit, { fits: 'fits appetite', tight: 'tight for the appetite', over: 'exceeds the appetite' }, forecast, forecast.appetiteEndsOn)
      const milestone = fitText(forecast.milestoneFit, { fits: 'fits the milestone', tight: 'tight for the milestone', over: 'misses the milestone' }, forecast, milestoneTargetOn)
      // Both explain at-risk, so both show, appetite first.
      const fit = [appetite, milestone].filter((t): t is string => !!t).join(' · ')
      if (fit) parts.push(fit)
      return parts.length > 0 ? parts.join(' · ') : 'No forecast'
    }
  }
}

function fitText(
  fit: ForecastFit | null | undefined,
  words: { fits: string; tight: string; over: string },
  f: WorkItemForecast,
  targetOn: string | null | undefined
): string | null {
  switch (fit) {
    case 'FITS':
      return words.fits
    case 'TIGHT':
      return words.tight
    case 'EXCEEDS': {
      const endMs = parseTargetOn(f.p85Date ?? f.p50Date)
      const targetMs = parseTargetOn(targetOn)
      if (endMs === null || targetMs === null) return words.over
      // Whole calendar days between two local midnights; rounding absorbs a 23 or 25 hour day.
      const over = Math.round((endMs - targetMs) / 86_400_000)
      return over > 0 ? `${words.over} by ${over} day${over === 1 ? '' : 's'}` : words.over
    }
    default:
      return null
  }
}

/** Replaces the whole list; a criterion sent again keeps its met state (matched by externalRef or text). */
export function replaceCriteria(
  projectId: string,
  workItemId: string,
  criteria: Array<{ text: string; kind?: 'auto' | 'manual'; externalRef?: string | null }>,
  token: string
): Promise<WorkItemCriterion[]> {
  return apiPut<WorkItemCriterion[]>(`${items(projectId)}/${workItemId}/criteria`, criteria, token)
}
