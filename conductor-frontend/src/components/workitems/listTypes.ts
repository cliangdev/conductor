// Shared shapes for the Work Item list surface (row, group, toolbar, bulk bar, and the
// useWorkItemListState hook all import from here instead of each redeclaring the same fields).

import type { MemberRole } from '@/types'
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

export interface Issue {
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
  /** Oldest first. Empty until something is recorded. */
  externalLinks?: WorkItemExternalLink[]
  /** Freeform labels, stored lower-cased. Empty until something is tagged. */
  tags?: string[]
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

export type SortKey = 'updated' | 'created' | 'title'

export const SORT_OPTIONS: { key: SortKey; label: string }[] = [
  { key: 'updated', label: 'Updated' },
  { key: 'created', label: 'Created' },
  { key: 'title', label: 'Title' },
]

export interface WorkItemGroupData {
  statusId: string
  label: string
  category: string
  items: IssueWithReviewers[]
}

/** One removable filter pill (type or status), rendered by FilterPills. */
export interface ActiveFilter {
  kind: 'type' | 'status' | 'tag'
  value: string
  label: string
}
