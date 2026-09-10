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
import { Modal } from '@/components/ui/modal'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiGet, apiPatch } from '@/lib/api'
import { statusMeta, statusHasReviewGate } from '@/lib/workflows'
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
  /**
   * Who may review this item, and who already does. When given and the next status is review-gated,
   * "Submit for review" first asks which of them should review it, so nobody submits into a review
   * with no reviewer and then hunts for the assignment control. Omit them and the button moves at once.
   */
  reviewers?: ReviewerChoice[]
  eligibleReviewers?: ReviewerChoice[]
  onAssignReviewer?: (userId: string) => Promise<void> | void
  onUnassignReviewer?: (userId: string) => Promise<void> | void
}

export interface ReviewerChoice {
  userId: string
  name: string
  email?: string
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
  reviewers,
  eligibleReviewers,
  onAssignReviewer,
  onUnassignReviewer,
}: PublishReadinessCardProps) {
  const [preflight, setPreflight] = useState<PublishPreflight | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [moving, setMoving] = useState(false)
  const [picking, setPicking] = useState(false)
  const [chosen, setChosen] = useState<Set<string>>(new Set())

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
  // The move lands in a review-gated status and the caller gave us people to choose from: ask first.
  const asksForReviewers =
    offersButton && eligibleReviewers !== undefined && statusHasReviewGate(workflowView, next!.to)

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

  function openPicker() {
    setChosen(new Set((reviewers ?? []).map((r) => r.userId)))
    setPicking(true)
  }

  async function submitWithReviewers() {
    const already = new Set((reviewers ?? []).map((r) => r.userId))
    setMoving(true)
    try {
      for (const userId of chosen) {
        if (!already.has(userId)) await onAssignReviewer?.(userId)
      }
      for (const userId of already) {
        if (!chosen.has(userId)) await onUnassignReviewer?.(userId)
      }
    } finally {
      setMoving(false)
    }
    setPicking(false)
    await move()
  }

  const gateRole = preflight.review.reviewerRole
  // Everyone offered is eligible; the ones already assigned are listed too, so the dialog shows the
  // whole set rather than only the additions.
  const choices = [...(reviewers ?? []), ...(eligibleReviewers ?? []).filter((m) => !(reviewers ?? []).some((r) => r.userId === m.userId))]

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
              onClick={() => (asksForReviewers ? openPicker() : void move())}
              disabled={!preflight.ready || moving}
              title={disabledReason}
            >
              {moving ? 'Moving…' : next!.label ?? statusMeta(workflowView, next!.to).label}
            </Button>
          )}
        </div>
      </CardHeader>
      {asksForReviewers && (
        <Modal
          open={picking}
          onOpenChange={(open) => {
            if (!moving) setPicking(open)
          }}
          title="Who should review it?"
          description={`Pick the people to approve this ${(workflowView?.noun ?? 'item').toLowerCase()}. Only members with the ${gateRole ? gateRole.toLowerCase() : 'reviewer'} or admin role can.`}
          footer={
            <div className="flex items-center justify-between gap-2">
              <span className="text-xs text-muted-foreground" aria-live="polite">
                {chosen.size === 0 ? 'Pick at least one reviewer.' : ''}
              </span>
              <div className="flex gap-2">
                <Button variant="ghost" onClick={() => setPicking(false)} disabled={moving}>
                  Cancel
                </Button>
                <Button onClick={() => void submitWithReviewers()} disabled={moving || chosen.size === 0}>
                  {moving ? 'Submitting…' : next!.label ?? 'Submit'}
                </Button>
              </div>
            </div>
          }
        >
          {choices.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Nobody in this workspace can review it yet. Invite someone with the reviewer or admin role first.
            </p>
          ) : (
            <ul className="space-y-2" aria-label="Reviewers">
              {choices.map((m) => (
                <li key={m.userId}>
                  <label className="flex items-center gap-2 text-sm">
                    <input
                      type="checkbox"
                      checked={chosen.has(m.userId)}
                      disabled={moving}
                      onChange={(e) =>
                        setChosen((prev) => {
                          const nextSet = new Set(prev)
                          if (e.target.checked) nextSet.add(m.userId)
                          else nextSet.delete(m.userId)
                          return nextSet
                        })
                      }
                    />
                    <span>{m.name}</span>
                    {m.email && <span className="text-xs text-muted-foreground">{m.email}</span>}
                  </label>
                </li>
              ))}
            </ul>
          )}
        </Modal>
      )}
      {(preflight.blockers.length > 0 || preflight.warnings.length > 0 || preflight.consent.required) && (
        // CardContent is a padless divide-y list for row-shaped children; these are prose and alerts, so
        // they carry the header's inset themselves.
        <CardContent className="space-y-2 divide-y-0 px-4 py-3">
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
