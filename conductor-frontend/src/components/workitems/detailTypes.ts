// Shared shapes for the Work Item detail surface (WorkItemDetailView, WorkItemPropertiesPanel,
// ReviewBar, ActivityTab) — split out so the properties panel and review bar don't each redeclare the
// same Issue/Reviewer/Review/Document fields as WorkItemDetailView.

import type { Issue } from '@/components/workitems/listTypes'
import type { Verdict } from '@/components/reviews/verdict'

// Reuses the list surface's Issue shape (id/title/type/status/displayId/assignee/workflow) rather
// than redeclaring it — createdAt/updatedAt are widened to optional (the detail fetch may omit them)
// and description/createdBy/createdByLabel are detail-only additions.
export interface DetailIssue extends Omit<Issue, 'createdAt' | 'updatedAt'> {
  description?: string
  createdBy?: string
  // Machine actor's display label (e.g. "Agent (ceo)"), set only when createdBy is null — see
  // WorkItemResponse.createdByLabel in openapi-v2.yaml. Byline falls back to this when there's no
  // human creator to resolve.
  createdByLabel?: string
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
  reviewVerdict?: Verdict
}

export interface DetailReview {
  reviewerId: string
  name: string
  avatarUrl?: string
  verdict: Verdict
  body?: string
  submittedAt: string
}
