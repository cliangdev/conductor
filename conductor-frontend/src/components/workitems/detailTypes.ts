// Shared shapes for the Work Item detail surface (WorkItemDetailView, WorkItemPropertiesPanel,
// ReviewBar, ActivityTab) — split out so the properties panel and review bar don't each redeclare the
// same Issue/Reviewer/Review/Document fields as WorkItemDetailView.

import type { Issue } from '@/components/workitems/listTypes'
import type { Verdict } from '@/components/reviews/verdict'

// Reuses the list surface's Issue shape (id/title/type/status/displayId/assignee/workflow) rather
// than redeclaring it — createdAt/updatedAt are widened to optional (the detail fetch may omit them)
// and description/createdBy are detail-only additions.
export interface DetailIssue extends Omit<Issue, 'createdAt' | 'updatedAt'> {
  description?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface DetailDocument {
  id: string
  filename: string
  contentType: string
  content?: string
  storageUrl?: string
  storageUrlExpiresAt?: string
}

export interface DetailReviewer {
  userId: string
  name: string
  email: string
  avatarUrl?: string
  /**
   * Not returned by the reviewers endpoint, which carries assignment only. Read the verdict off
   * `DetailReview` instead — anything counting approvals from here counts zero.
   */
  reviewVerdict?: Verdict
}

export interface DetailReview {
  reviewerId: string
  name: string
  avatarUrl?: string
  verdict: Verdict
  body?: string
  submittedAt: string
  /**
   * Whether this review still describes the item as it stands, and so still counts toward the review
   * gate. False for one cast in an earlier review round, or against a different publish bundle —
   * changing a Post's caption, schedule, targets or media withdraws an approval. Absent from an older
   * server, where it is treated as standing so the count degrades to the pre-existing behaviour rather
   * than reading zero.
   */
  current?: boolean
}
