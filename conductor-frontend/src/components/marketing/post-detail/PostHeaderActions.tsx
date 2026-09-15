'use client'

// The Post page's header actions: the readiness sentence and the one primary move (the author's), or
// the assigned reviewer's verdict pair — and beside either, the More menu with every other move. One
// primary action per screen; the rest one click away.

import { Button } from '@/components/ui/button'
import { PublishReadinessAction, type PublishReadinessState } from '@/components/marketing/publishReadiness'

export interface PostHeaderActionsProps {
  readiness: PublishReadinessState
  /** The viewer is the assigned reviewer of this Post: Approve / Request changes are theirs. */
  reviewerActs: boolean
  reviewSubmitting: boolean
  /** Approving behind an unmet gate records a verdict the Workflow cannot act on, so it waits. */
  gateReady: boolean
  onApprove: () => void
  onRequestChanges: () => void
  menu: React.ReactNode
}

export function PostHeaderActions({
  readiness,
  reviewerActs,
  reviewSubmitting,
  gateReady,
  onApprove,
  onRequestChanges,
  menu,
}: PostHeaderActionsProps) {
  return (
    <div className="flex flex-wrap items-center justify-end gap-2" data-testid="post-header-actions">
      <PublishReadinessAction state={readiness} forReviewer={reviewerActs} />
      {reviewerActs && (
        <>
          <Button variant="outline" size="sm" onClick={onRequestChanges} disabled={reviewSubmitting}>
            Request changes
          </Button>
          <Button size="sm" onClick={onApprove} disabled={reviewSubmitting || !gateReady}>
            Approve
          </Button>
        </>
      )}
      {menu}
    </div>
  )
}
