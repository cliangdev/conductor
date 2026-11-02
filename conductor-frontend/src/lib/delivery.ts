// Delivery tracking reads and writes (docs/prd-tracking.md): the tree under a PRD, dependencies,
// criteria, milestones. One copy of each call so the Plan tab, properties panel and roadmap agree on
// paths and shapes.

import { apiDelete, apiGet, apiPatch, apiPost, apiPut } from './api'
import type {
  DependencyGraph,
  DependencyKind,
  Milestone,
  MilestoneState,
  WorkItemCriterion,
  WorkItemDependencies,
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

/** Replaces the whole list; a criterion sent again keeps its met state (matched by externalRef or text). */
export function replaceCriteria(
  projectId: string,
  workItemId: string,
  criteria: Array<{ text: string; kind?: 'auto' | 'manual'; externalRef?: string | null }>,
  token: string
): Promise<WorkItemCriterion[]> {
  return apiPut<WorkItemCriterion[]>(`${items(projectId)}/${workItemId}/criteria`, criteria, token)
}
