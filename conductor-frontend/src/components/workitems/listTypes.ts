// Shared shapes for the Work Item list surface (row, group, toolbar, bulk bar, and the
// useWorkItemListState hook all import from here instead of each redeclaring the same fields).

import type { MemberRole } from '@/types'

export interface IssueAssignee {
  userId: string
  name: string
  avatarUrl?: string | null
}

export interface IssueReviewer {
  userId: string
  name: string
  avatarUrl?: string
  reviewVerdict?: 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'
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
  kind: 'type' | 'status'
  value: string
  label: string
}
