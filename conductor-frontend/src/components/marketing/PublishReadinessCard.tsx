'use client'

// "What is still in the way?" — the approval gate's own answer, on the Post, before anyone tries to move it.
//
// Until now the gate was discoverable only by attempting the transition: pick "Submit for review" from the
// status menu, get a toast with a 422, guess which panel it meant. The server now exposes the same verdict
// on demand (GET .../publish-preflight), so this card shows it: every blocker with the destination it is
// about, the advisories that will not stop anything, whether a review currently opens the gate, whether
// the creator's consent stands, and the one move that is next — as a button that is disabled with the
// first blocker as its reason rather than a menu item that fails after the click.
//
// Nothing here decides anything. The card renders what the server said; the server is asked again after
// every edit (the parent bumps `refreshKey`) and after the button lands.

import { useCallback, useEffect, useState } from 'react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiGet, apiPatch } from '@/lib/api'
import { statusMeta } from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'

export interface PublishFinding {
  code: string
  message: string
  targetId?: string | null
}

export interface PublishPreflight {
  publishing: boolean
  ready: boolean
  blockers: PublishFinding[]
  warnings: PublishFinding[]
  nextTransition?: { to: string; label?: string | null; requiresReview: boolean } | null
  consent: { required: boolean; verdict: string }
  review: { gated: boolean; assignedReviewers: number; satisfied: boolean; reviewerRole?: string | null }
  earliestFireTime?: string | null
}

export interface PublishReadinessCardProps {
  projectId: string
  workItemId: string
  token: string
  status: string
  userRole: 'ADMIN' | 'CREATOR' | 'REVIEWER'
  workflowView?: WorkflowView
  /** Change it to make the card ask the server again — after any edit that touches the bundle. */
  refreshKey?: number | string
  onStatusChanged?: (status: string) => void
}

/** The sentence under the heading: where the item is in the pipeline, in the gate's own terms. */
function summary(preflight: PublishPreflight, view: WorkflowView | undefined, status: string): string {
  const noun = view?.noun ?? 'Work Item'
  if (!preflight.nextTransition) {
    return `This ${noun.toLowerCase()} is ${statusMeta(view, status).label.toLowerCase()}; there is nothing left to check here.`
  }
  if (!preflight.ready) {
    const n = preflight.blockers.length
    return `${n} thing${n === 1 ? '' : 's'} to fix before it can move on.`
  }
  if (preflight.nextTransition.requiresReview) {
    if (preflight.review.satisfied) return 'Approved and ready to move on.'
    if (preflight.review.assignedReviewers === 0) {
      return `Everything checks out. It needs a reviewer${preflight.review.reviewerRole ? ` (${preflight.review.reviewerRole.toLowerCase()} role)` : ''} to be assigned and to approve.`
    }
    return `Everything checks out. Waiting on ${preflight.review.assignedReviewers} reviewer${preflight.review.assignedReviewers === 1 ? '' : 's'}; approval schedules it automatically.`
  }
  if (preflight.review.gated && preflight.review.assignedReviewers === 0) {
    return 'Everything checks out. Assign a reviewer so it can be approved once you submit it.'
  }
  return 'Everything checks out.'
}

export function PublishReadinessCard({
  projectId,
  workItemId,
  token,
  status,
  userRole,
  workflowView,
  refreshKey,
  onStatusChanged,
}: PublishReadinessCardProps) {
  const [preflight, setPreflight] = useState<PublishPreflight | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [moving, setMoving] = useState(false)

  const load = useCallback(async () => {
    try {
      const data = await apiGet<PublishPreflight>(
        `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-preflight`,
        token
      )
      setPreflight(data)
      setError(null)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not check whether this is ready to publish'))
    }
  }, [projectId, workItemId, token])

  useEffect(() => {
    void load()
    // `status` and `refreshKey` are the parent's signals that the answer may have moved.
  }, [load, status, refreshKey])

  if (error) {
    return <Alert variant="warning">{error}</Alert>
  }
  if (!preflight || !preflight.publishing) {
    return null
  }

  const next = preflight.nextTransition
  const canMove = userRole !== 'REVIEWER' && next !== null && next !== undefined
  // A review-gated move is the reviewer's to make with their verdict, not a button here — and after an
  // approval the Workflow takes it on its own. What this button offers is the author's own move.
  const offersButton = canMove && !next!.requiresReview
  const disabledReason = !preflight.ready ? preflight.blockers[0]?.message : undefined

  async function move() {
    if (!next) return
    setMoving(true)
    try {
      await apiPatch(`/api/v2/projects/${projectId}/work-items/${workItemId}`, { status: next.to }, token)
      onStatusChanged?.(next.to)
      await load()
    } catch (err) {
      toastError(apiErrorMessage(err, 'The move was refused'))
      await load()
    } finally {
      setMoving(false)
    }
  }

  return (
    <Card data-testid="publish-readiness">
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <h3 className="text-sm font-semibold text-foreground">
              {preflight.ready ? 'Ready to publish' : 'Not ready yet'}
            </h3>
            <p className="text-sm text-muted-foreground">{summary(preflight, workflowView, status)}</p>
          </div>
          {offersButton && (
            <Button
              size="sm"
              onClick={() => void move()}
              disabled={!preflight.ready || moving}
              title={disabledReason}
            >
              {moving ? 'Moving…' : next!.label ?? statusMeta(workflowView, next!.to).label}
            </Button>
          )}
        </div>
      </CardHeader>
      {(preflight.blockers.length > 0 || preflight.warnings.length > 0 || preflight.consent.required) && (
        <CardContent className="space-y-2">
          {preflight.blockers.length > 0 && (
            <ul className="space-y-1" aria-label="Blockers">
              {preflight.blockers.map((finding, index) => (
                <li key={`${finding.code}-${index}`}>
                  <Alert variant="destructive">{finding.message}</Alert>
                </li>
              ))}
            </ul>
          )}
          {preflight.warnings.length > 0 && (
            <ul className="space-y-1" aria-label="Warnings">
              {preflight.warnings.map((finding, index) => (
                <li key={`${finding.code}-${index}`}>
                  <Alert variant="warning">{finding.message}</Alert>
                </li>
              ))}
            </ul>
          )}
          {preflight.consent.required && preflight.consent.verdict === 'VALID' && (
            <p className="text-xs text-muted-foreground">The creator&rsquo;s TikTok consent stands for this version.</p>
          )}
        </CardContent>
      )}
    </Card>
  )
}
