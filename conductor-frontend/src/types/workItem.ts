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
  defaultView: string
  version: number
  types: string[]
  assetTypes?: string[]
  statuses: WorkflowStatusView[]
  transitions: WorkflowTransitionView[]
  metric?: WorkflowMetricView | null
}

/** One published version of a Workflow (GET .../workflows/{workflowId}/versions). */
export interface WorkflowVersionSummary {
  version: number
  schemaVersion?: number | null
  publishedAt: string
  publishedBy?: string | null
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
