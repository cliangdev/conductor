// Shared shapes for the Work Item list surface (row, group, toolbar, bulk bar, and the
// useWorkItemListState hook all import from here instead of each redeclaring the same fields).

import type { MemberRole } from '@/types'
import type { WorkItemDeliveryFields } from '@/types/workItem'
import type { Verdict } from '@/components/reviews/verdict'

export interface IssueAssignee {
  userId: string
  name: string
  avatarUrl?: string | null
}

export interface IssueReviewer {
  userId: string
  name: string
  avatarUrl?: string
  reviewVerdict?: Verdict
}

/**
 * Where a Work Item ended up outside Conductor: one of its recorded link Assets.
 *
 * Workflow-agnostic on purpose — it is whatever the item's own `asset_types` allow, so a Post carries the
 * live posts it published to and an Issue carries its pull request, and no list surface has to know which
 * is which.
 */
export interface WorkItemExternalLink {
  url: string
  /** The Asset type, a Workflow-defined string (e.g. `instagram_post`, `github_pr`). */
  type: string
  label?: string | null
}

/** One publish destination on a Post and how it stands (list response only). */
export interface WorkItemPublishTarget {
  platform: string
  /** PENDING | HANDED_OFF | PUBLISHING | AWAITING_MANUAL | PUBLISHED | FAILED | REVOKED */
  state: string
  stateLabel?: string
  label?: string | null
}

/** The Creative a Post's media came from (list response only). */
export interface WorkItemCreativeRef {
  id: string
  displayId: string
  name?: string | null
}

export interface Issue extends WorkItemDeliveryFields {
  id: string
  title: string
  type: string
  status: string
  createdAt: string
  updatedAt: string
  unresolvedCommentCount?: number
  displayId?: string
  sequenceNumber?: number
  assignee?: IssueAssignee | null
  workflow?: string
  scheduledFor?: string | null
  scheduleTimezone?: string | null
  /** Publish as soon as approved: no date is needed, entering the scheduled status stamps one. */
  publishOnApproval?: boolean
  /** Oldest first. Empty until something is recorded. */
  externalLinks?: WorkItemExternalLink[]
  /** Freeform labels, stored lower-cased. Empty until something is tagged. */
  tags?: string[]
  /** Calendar date (yyyy-MM-dd) this item is due, or null. */
  dueOn?: string | null
  /** List response only: signed URL of the first image attached, or null. */
  thumbnailUrl?: string | null
  /** List response only: each destination with its state. */
  publishTargets?: WorkItemPublishTarget[]
  /** List response only: the Creative the media came from, or null. */
  creative?: WorkItemCreativeRef | null
  /** List response only: true when the extras could not be worked out, so whether this Post is live is UNKNOWN. */
  enrichmentFailed?: boolean | null
}

export interface IssueWithReviewers extends Issue {
  reviewers?: IssueReviewer[]
}

export interface Member {
  userId: string
  name: string
  email: string
  avatarUrl?: string | null
  role: MemberRole
}

export type ListView = 'active' | 'done' | 'all'

export type SortKey = 'updated' | 'created' | 'title' | 'priority' | 'dueOn'

export const SORT_OPTIONS: { key: SortKey; label: string }[] = [
  { key: 'updated', label: 'Updated' },
  { key: 'created', label: 'Created' },
  { key: 'title', label: 'Title' },
  { key: 'priority', label: 'Priority' },
  { key: 'dueOn', label: 'Due date' },
]

export function isSortKey(v: string): v is SortKey {
  return SORT_OPTIONS.some((o) => o.key === v)
}

export interface WorkItemGroupData {
  statusId: string
  label: string
  category: string
  items: IssueWithReviewers[]
}

/** The derived signals a filter pill can ask for (docs/prd-tracking.md). */
export type SignalFilter = 'blocked' | 'ready' | 'needsInput' | 'stale'

export const SIGNAL_FILTERS: { value: SignalFilter; label: string }[] = [
  { value: 'blocked', label: 'Blocked' },
  { value: 'ready', label: 'Ready' },
  { value: 'needsInput', label: 'Needs input' },
  { value: 'stale', label: 'Stale' },
]

/** One removable filter pill (type, status, tag, priority, milestone or signal), rendered by FilterPills. */
export interface ActiveFilter {
  kind: 'type' | 'status' | 'tag' | 'priority' | 'milestone' | 'signal'
  value: string
  label: string
}
