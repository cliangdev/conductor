// COND-18: shared types for the customizable Work Item / Workflow (statechart) model.
//
// A "Work Item" is the generalized issue: its `type` and `status` are plain strings defined by the
// Workflow it is bound to (no fixed enum). The display metadata for those strings — labels, colors,
// categories, which moves are review-gated — lives in a `WorkflowView`, fetched per slug and cached.

/** Lane grouping a status belongs to. Drives Active/Done bucketing and color. */
export type WorkflowStatusCategory = 'open' | 'in_progress' | 'terminal'

/** A review verdict, as expressed in a statechart's `reviewOutcomes`. */
export type ReviewOutcome = 'approve' | 'request_changes' | 'comment'

export interface WorkflowStatusView {
  id: string
  /** Human-friendly display name; falls back to the id when absent. */
  label: string
  /** open | in_progress | terminal (kept widened to string for forward-compat). */
  category: WorkflowStatusCategory | string
  initial?: boolean
  terminal?: boolean
}

export interface WorkflowTransitionView {
  from: string
  to: string
  label: string
  requiresReview?: boolean
  reviewerRole?: string | null
  trigger?: string | null
  /**
   * Allowed review verdicts for a review-gated edge. Not part of the lean WorkflowView contract
   * today, but the statechart carries it — kept here so the review form can narrow its options
   * once the backend surfaces it.
   */
  reviewOutcomes?: ReviewOutcome[]
}

export interface WorkflowMetricView {
  name: string
  unit?: string | null
  direction: string
}

/** Lean, render-ready projection of a Workflow's statechart (GET .../workflows/by-slug/{slug}). */
export interface WorkflowView {
  slug: string
  noun: string
  /** Nav-grouping slug from the statechart (e.g. "ENGINEERING"); used for breadcrumbs. Absent when unset. */
  area?: string | null
  defaultView: string
  version: number
  types: string[]
  assetTypes?: string[]
  statuses: WorkflowStatusView[]
  transitions: WorkflowTransitionView[]
  metric?: WorkflowMetricView | null
  /** Typed fields a Work Item on this Workflow carries, in display order. Absent when it declares none. */
  fields?: WorkflowFieldView[]
  /** What the Workflow calls a Work Item's due date (e.g. "Review on"). */
  dueLabel?: string | null
}

export type WorkflowFieldType = 'text' | 'longtext' | 'date' | 'number' | 'enum' | 'url'

export interface WorkflowFieldView {
  key: string
  label: string
  type: WorkflowFieldType
  /** Allowed values; present for enum fields. */
  options?: string[]
  /** Status ids a Work Item cannot move into while this field is empty. */
  requiredIn?: string[]
}

/** One published version of a Workflow (GET .../workflows/{workflowId}/versions). */
export interface WorkflowVersionSummary {
  version: number
  schemaVersion?: number | null
  publishedAt: string
  publishedBy?: string | null
  /** True for the currently active version. */
  active: boolean
  /** Work items pinned to this version. */
  workItemCount: number
  changeSummary: {
    statusesAdded: string[]
    statusesRemoved: string[]
  }
}

/** Response envelope from GET .../workflows/{workflowId}/versions. */
export interface WorkflowVersionsResponse {
  activeVersion?: number | null
  totalWorkItems: number
  versions: WorkflowVersionSummary[]
}

export interface WorkItemAssignee {
  userId: string
  name: string
  avatarUrl?: string | null
}

/** The generalized issue. `type`/`status` are Workflow-defined strings, resolved via a WorkflowView. */
export interface WorkItem {
  id: string
  projectId: string
  type: string
  status: string
  title: string
  description?: string
  assignee?: WorkItemAssignee | null
  sequenceNumber: number
  displayId: string
  unresolvedCommentCount?: number
  /**
   * Slug of the Workflow this Work Item is bound to. Not present in IssueResponse today (every Work
   * Item is ENGINEERING) — callers default to {@link DEFAULT_WORKFLOW_SLUG} when absent.
   */
  workflow?: string
}

// --- Delivery tracking (see docs/prd-tracking.md) -------------------------------------------------

/** P0 is the most urgent; absent means the item has no priority. */
export type WorkItemPriority = 'P0' | 'P1' | 'P2' | 'P3'

/** Leaf-level rollup over an item's subtree: done = terminal in the leaf's own Workflow. */
export interface WorkItemProgress {
  total: number
  done: number
  percent: number
  criteriaTotal: number
  criteriaMet: number
}

export interface WorkItemBlocker {
  id: string
  displayId: string
  title: string
}

/** Facts derived on read from dependencies, status and agent sessions; never typed by a person. */
export interface WorkItemSignals {
  blocked: boolean
  /** Only the blockers that have not finished. */
  blockedBy: WorkItemBlocker[]
  ready: boolean
  stale: boolean
  needsInput: boolean
}

export type AgentSessionMode = 'DELEGATE' | 'ASSIGNEE'
export type AgentSessionActivity = 'WORKING' | 'NEEDS_INPUT' | 'DONE' | 'ERRORED'

/** The latest open agent session on an item. */
export interface WorkItemAgentActivity {
  agentLabel: string
  mode: AgentSessionMode
  activity: AgentSessionActivity
  note?: string | null
  updatedAt: string
}

/** The fields every Work Item response carries for delivery tracking. */
export interface WorkItemDeliveryFields {
  parentWorkItemId?: string | null
  priority?: WorkItemPriority | null
  milestoneId?: string | null
  milestoneName?: string | null
  /** Normalised time-box label, e.g. "2 weeks". */
  appetite?: string | null
  appetiteDays?: number | null
  startedAt?: string | null
  completedAt?: string | null
  /** Direct children. Lists show "N sub-items" instead of the children themselves. */
  childCount?: number
  /** Present on single reads and on lists fetched with `withProgress=true`. */
  progress?: WorkItemProgress | null
  signals?: WorkItemSignals | null
  agentActivity?: WorkItemAgentActivity | null
  health?: WorkItemHealth | null
}

/** One node of GET .../work-items/{id}/tree. */
export interface WorkItemTreeNode {
  id: string
  displayId: string
  title: string
  type: string
  workflow?: string | null
  status: string
  statusLabel?: string | null
  terminal?: boolean
  priority?: WorkItemPriority | null
  parentWorkItemId?: string | null
  milestoneId?: string | null
  assignee?: WorkItemAssignee | null
  childCount: number
  /** This node's own criteria, not a rollup. */
  criteriaTotal: number
  criteriaMet: number
  signals?: WorkItemSignals | null
  agentActivity?: WorkItemAgentActivity | null
  children: WorkItemTreeNode[]
}

export type DependencyKind = 'BLOCKS' | 'DISCOVERED_FROM'

export interface WorkItemDependencyEntry {
  dependencyId: string
  workItemId: string
  displayId: string
  title: string
  status: string
}

/** GET .../work-items/{id}/dependencies, grouped by direction. */
export interface WorkItemDependencies {
  blocks: WorkItemDependencyEntry[]
  blockedBy: WorkItemDependencyEntry[]
  discoveredFrom: WorkItemDependencyEntry[]
  discovered: WorkItemDependencyEntry[]
}

export interface DependencyGraphNode {
  id: string
  displayId: string
  title: string
  status: string
  statusCategory?: WorkflowStatusCategory | string | null
  /** A blocker outside the subtree being drawn. */
  external: boolean
}

export interface DependencyGraphEdge {
  id: string
  /** The blocking item. */
  from: string
  to: string
  kind: DependencyKind
}

export interface DependencyGraph {
  nodes: DependencyGraphNode[]
  edges: DependencyGraphEdge[]
}

export type CriterionKind = 'auto' | 'manual'

export interface WorkItemCriterion {
  criterionId: string
  position: number
  text: string
  kind: CriterionKind
  /** The PRD's AC id this came from, e.g. AC-P0-1.1. */
  externalRef?: string | null
  met: boolean
  evidence?: string | null
  metAt?: string | null
  metByLabel?: string | null
}

export type MilestoneState = 'OPEN' | 'CLOSED'

export interface MilestoneProgress {
  topLevelTotal: number
  topLevelDone: number
  leafTotal: number
  leafDone: number
}

export interface Milestone {
  id: string
  projectId: string
  name: string
  description?: string | null
  targetOn?: string | null
  state: MilestoneState
  sortOrder: number
  progress?: MilestoneProgress | null
  createdAt: string
  updatedAt: string
}

/** A produced output linked to a Work Item (GET .../issues/{issueId}/assets). */
export interface WorkItemAsset {
  id: string
  issueId: string
  type: string
  label?: string
  kind: 'link' | 'file'
  ref: string
  done: boolean
  createdAt: string
  updatedAt: string
}

// --- Health and forecast (docs/prd-tracking.md) ---------------------------------------------------

export type HealthStatus = 'ON_TRACK' | 'AT_RISK' | 'OFF_TRACK'

export interface WorkItemHealthDraft {
  updateId: string
  health: HealthStatus
  note: string
  draftedByLabel?: string | null
  createdAt: string
}

/** The latest confirmed health update, plus any pending draft awaiting a person. */
export interface WorkItemHealth {
  health: HealthStatus | null
  note?: string | null
  updatedAt?: string | null
  stale: boolean
  atRisk: boolean
  pendingDraft?: WorkItemHealthDraft | null
}

/** One row of GET/POST .../health-updates (schema WorkItemHealthUpdateResponse). */
export interface HealthUpdate {
  id: string
  health: HealthStatus
  note: string
  draftedByLabel?: string | null
  confirmed: boolean
  confirmedByName?: string | null
  confirmedAt?: string | null
  createdAt: string
}

export type ForecastFit = 'FITS' | 'TIGHT' | 'EXCEEDS'

export interface WorkItemForecast {
  state: 'DONE' | 'NO_PLAN' | 'INSUFFICIENT_DATA' | 'OK'
  mode?: 'THROUGHPUT' | 'CYCLE_TIME' | null
  remaining: number
  samples: number
  needed?: number | null
  weeklyThroughputMedian?: number | null
  /** `YYYY-MM-DD`. */
  p50Date?: string | null
  p85Date?: string | null
  appetiteEndsOn?: string | null
  appetiteFit?: ForecastFit | null
  milestoneFit?: ForecastFit | null
}

export interface DeliveryItemSummary {
  id: string
  displayId: string
  title: string
  type: string
  status: string
  statusLabel?: string | null
  workflow?: string | null
  priority?: WorkItemPriority | null
  parentWorkItemId?: string | null
  milestoneId?: string | null
  milestoneName?: string | null
  appetite?: string | null
  appetiteDays?: number | null
  createdAt?: string | null
  startedAt?: string | null
  /** How many unfinished blockers hold the item up; set on the blocked attention list, 0 elsewhere. */
  blockedByCount?: number
}

export interface DeliveryActiveItem {
  item: DeliveryItemSummary
  progress: WorkItemProgress
  health?: WorkItemHealth | null
  forecast: WorkItemForecast
  signals: WorkItemSignals
}

/** GET .../projects/{id}/delivery-overview. */
export interface DeliveryOverview {
  activeItems: DeliveryActiveItem[]
  milestones: Milestone[]
  attention: {
    blocked: DeliveryItemSummary[]
    stale: DeliveryItemSummary[]
    needsInput: DeliveryItemSummary[]
  }
}
