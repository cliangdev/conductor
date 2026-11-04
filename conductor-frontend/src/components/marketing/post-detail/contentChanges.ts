// A Post's content changes: edits to the slide text of a Creative it uses, carried onto the Post (and the
// review round each one opened or stayed in). Read from GET .../work-items/{id}/content-changes.

import { apiGet } from '@/lib/api'
import type { RevisionSource, TextChange } from '@/components/marketing/creatives/slideText'
import type { DetailReview } from '@/components/workitems/detailTypes'

export interface PostContentChange {
  id: string
  creativeId: string
  creativeDisplayId?: string | null
  revisionId: string
  revisionNo?: number | null
  source: Extract<RevisionSource, 'EDITOR' | 'REVIEWER' | 'MCP'>
  actorUserId?: string | null
  actorName?: string | null
  fromStatus?: string | null
  toStatus?: string | null
  reviewRoundBefore: number
  reviewRoundAfter: number
  textDiff: TextChange[]
  createdAt: string
}

/** Newest first, at most 50. */
export async function getContentChanges(projectId: string, workItemId: string, token: string): Promise<PostContentChange[]> {
  const res = await apiGet<{ changes: PostContentChange[] }>(`/api/v2/projects/${projectId}/work-items/${workItemId}/content-changes`, token)
  return res.changes ?? []
}

/**
 * The change to show as "Changed since last review": the newest one, when it opened the review round the Post
 * is in (or is a reviewer's fix inside it). A change made while the Post was still a draft is not one: nobody
 * has reviewed anything yet. When the server does not report the current round (an older server), the newest
 * change counts until a review has been submitted after it.
 */
export function changedSinceLastReview(
  changes: readonly PostContentChange[],
  currentRound: number | null | undefined,
  reviews: readonly Pick<DetailReview, 'submittedAt'>[] = [],
): PostContentChange | null {
  const newest = changes[0]
  if (!newest) return null
  const touchedReview = newest.reviewRoundAfter > newest.reviewRoundBefore || newest.source === 'REVIEWER'
  if (!touchedReview) return null
  if (typeof currentRound === 'number') return newest.reviewRoundAfter === currentRound ? newest : null
  const at = new Date(newest.createdAt).getTime()
  return reviews.some((r) => new Date(r.submittedAt).getTime() > at) ? null : newest
}
