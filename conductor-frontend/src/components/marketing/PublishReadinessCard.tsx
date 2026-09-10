'use client'

// "What is still in the way?" — the approval gate's own answer, on the Post, before anyone tries to move it.
//
// Until now the gate was discoverable only by attempting the transition: pick "Submit for review" from the
// status menu, get a toast with a 422, guess which panel it meant. The server now exposes the same verdict
// on demand (GET .../publish-preflight), so this is shown: every blocker with the destination it is about,
// the advisories that will not stop anything, whether a review currently opens the gate, whether the
// creator's consent stands, and the one move that is next — as a button that is disabled with the first
// blocker as its reason rather than a menu item that fails after the click.
//
// The move — the button and its one-line summary — is the page's one primary action, so it lives in the
// PageHeader's actions slot (see PublishReadinessAction). The blockers, warnings and consent footnote stay
// as a card at the top of the reading column, because that is where an author looks after an edit. Both
// pieces read one `usePublishReadiness` state, lifted into the parent so there is exactly one poll of the
// server and exactly one reviewer-picker dialog, not two of each.
//
// Nothing here decides anything. It renders what the server said; the server is asked again after every
// edit (the parent bumps `refreshKey`) and after the button lands.

import { useCallback, useEffect, useRef, useState } from 'react'
import { Modal } from '@/components/ui/modal'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
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

export interface ReviewerChoice {
  userId: string
  name: string
  email?: string
}

export interface UsePublishReadinessArgs {
  projectId: string
  workItemId: string
  token: string
  status: string
  userRole: 'ADMIN' | 'CREATOR' | 'REVIEWER'
  workflowView?: WorkflowView
  /** Change it to make the state ask the server again — after any edit that touches the bundle. */
  refreshKey?: number | string
  onStatusChanged?: (status: string) => void
  /** Every answer the server gives, so the page can keep the status menu honest about the same move. */
  onPreflight?: (preflight: PublishPreflight) => void
  /**
   * Who may review this item, and who already does. When given and the next status is review-gated,
   * the move button first asks which of them should review it, so nobody submits into a review with
   * no reviewer and then hunts for the assignment control. Omit them and the button moves at once.
   */
  reviewers?: ReviewerChoice[]
  eligibleReviewers?: ReviewerChoice[]
  onAssignReviewer?: (userId: string) => Promise<void> | void
  onUnassignReviewer?: (userId: string) => Promise<void> | void
}

export interface PublishReadinessState {
  preflight: PublishPreflight | null
  error: string | null
  moving: boolean
  picking: boolean
  setPicking: (open: boolean) => void
  chosen: Set<string>
  setChosen: React.Dispatch<React.SetStateAction<Set<string>>>
  next: PublishPreflight['nextTransition'] | undefined
  /** True for anyone but a REVIEWER, with a move actually on offer. */
  canMove: boolean
  /** The move belongs here as a button — not a review-gated transition, which is a reviewer's verdict. */
  offersButton: boolean
  disabledReason?: string
  asksForReviewers: boolean
  gateRole?: string | null
  /** Everyone offered in the reviewer-picker dialog: those assigned already, plus who else is eligible. */
  choices: ReviewerChoice[]
  move: () => Promise<void>
  openPicker: () => void
  submitWithReviewers: () => Promise<void>
  /** The one-line summary under the title, or beside the button in the header. Null while unreadable/loading. */
  summaryText: string | null
  buttonLabel: string
  workflowView?: WorkflowView
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

/**
 * Owns the one poll of `publish-preflight` and the one reviewer-picker dialog, so the header action and
 * the findings card downstream read the same answer instead of asking the server twice and popping two
 * dialogs.
 */
export function usePublishReadiness({
  projectId,
  workItemId,
  token,
  status,
  userRole,
  workflowView,
  refreshKey,
  onStatusChanged,
  onPreflight,
  reviewers,
  eligibleReviewers,
  onAssignReviewer,
  onUnassignReviewer,
}: UsePublishReadinessArgs): PublishReadinessState {
  const [preflight, setPreflight] = useState<PublishPreflight | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [moving, setMoving] = useState(false)
  const [picking, setPicking] = useState(false)
  // The parent's handler is an inline arrow; a ref keeps it out of load's dependencies.
  const onPreflightRef = useRef(onPreflight)
  useEffect(() => {
    onPreflightRef.current = onPreflight
  }, [onPreflight])
  const [chosen, setChosen] = useState<Set<string>>(new Set())

  const load = useCallback(async () => {
    try {
      const data = await apiGet<PublishPreflight>(
        `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-preflight`,
        token
      )
      setPreflight(data)
      setError(null)
      onPreflightRef.current?.(data)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not check whether this is ready to publish'))
    }
  }, [projectId, workItemId, token])

  useEffect(() => {
    void load()
    // `status` and `refreshKey` are the parent's signals that the answer may have moved.
  }, [load, status, refreshKey])

  const next = preflight?.nextTransition ?? undefined
  const canMove = userRole !== 'REVIEWER' && next !== null && next !== undefined
  // A review-gated move is the reviewer's to make with their verdict, not a button here — and after an
  // approval the Workflow takes it on its own. What this button offers is the author's own move.
  const offersButton = canMove && !next?.requiresReview
  const disabledReason = preflight && !preflight.ready ? preflight.blockers?.[0]?.message : undefined
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

  const gateRole = preflight?.review?.reviewerRole
  // Everyone offered is eligible; the ones already assigned are listed too, so the dialog shows the
  // whole set rather than only the additions.
  const choices = [
    ...(reviewers ?? []),
    ...(eligibleReviewers ?? []).filter((m) => !(reviewers ?? []).some((r) => r.userId === m.userId)),
  ]

  const summaryText =
    error ? null : preflight && preflight.publishing ? summary(preflight, workflowView, status) : null
  const buttonLabel = moving
    ? 'Moving…'
    : next
      ? (next.label ?? statusMeta(workflowView, next.to).label)
      : ''

  return {
    preflight,
    error,
    moving,
    picking,
    setPicking,
    chosen,
    setChosen,
    next,
    canMove,
    offersButton,
    disabledReason,
    asksForReviewers,
    gateRole,
    choices,
    move,
    openPicker,
    submitWithReviewers,
    summaryText,
    buttonLabel,
    workflowView,
  }
}

/**
 * The page's one primary action — the move button and its one-line summary — for the PageHeader's
 * actions slot. Renders nothing until the preflight answer is in, or for a Work Item whose Workflow
 * doesn't publish, or for a REVIEWER (whose move is a verdict on the review bar, not this button).
 */
export function PublishReadinessAction({
  state,
  forReviewer = false,
}: {
  state: PublishReadinessState
  /**
   * The viewer is the assigned reviewer with Approve / Request changes beside this. Then "waiting on a
   * reviewer" is about them and says nothing, so the sentence is dropped while the gate is satisfied;
   * while it is not, it says what has to change before an approval would take.
   */
  forReviewer?: boolean
}) {
  const { preflight, error, workflowView } = state

  if (error) {
    return <Alert variant="warning">{error}</Alert>
  }
  if (!preflight || !preflight.publishing) {
    return null
  }

  const blockerCount = preflight.blockers.length
  const summaryText = forReviewer
    ? preflight.ready
      ? null
      : `${blockerCount} thing${blockerCount === 1 ? '' : 's'} to fix before it can be approved. Send it back to the author.`
    : state.summaryText

  return (
    <div className="flex flex-wrap items-center justify-end gap-3">
      {summaryText && <p className="text-sm text-muted-foreground">{summaryText}</p>}
      {state.offersButton && (
        <Button
          size="sm"
          onClick={() => (state.asksForReviewers ? state.openPicker() : void state.move())}
          disabled={!preflight.ready || state.moving}
          title={state.disabledReason}
        >
          {state.buttonLabel}
        </Button>
      )}
      {state.asksForReviewers && (
        <Modal
          open={state.picking}
          onOpenChange={(open) => {
            if (!state.moving) state.setPicking(open)
          }}
          title="Who should review it?"
          description={`Pick the people to approve this ${(workflowView?.noun ?? 'item').toLowerCase()}. Only members with the ${state.gateRole ? state.gateRole.toLowerCase() : 'reviewer'} or admin role can.`}
          footer={
            <div className="flex items-center justify-between gap-2">
              <span className="text-sm text-muted-foreground" aria-live="polite">
                {state.chosen.size === 0 ? 'Pick at least one reviewer.' : ''}
              </span>
              <div className="flex gap-2">
                <Button variant="ghost" onClick={() => state.setPicking(false)} disabled={state.moving}>
                  Cancel
                </Button>
                <Button
                  onClick={() => void state.submitWithReviewers()}
                  disabled={state.moving || state.chosen.size === 0}
                >
                  {state.moving ? 'Submitting…' : (state.next?.label ?? 'Submit')}
                </Button>
              </div>
            </div>
          }
        >
          {state.choices.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Nobody in this workspace can review it yet. Invite someone with the reviewer or admin role first.
            </p>
          ) : (
            <ul className="space-y-2" aria-label="Reviewers">
              {state.choices.map((m) => (
                <li key={m.userId}>
                  <Checkbox
                    checked={state.chosen.has(m.userId)}
                    disabled={state.moving}
                    onCheckedChange={(checked) =>
                      state.setChosen((prev) => {
                        const nextSet = new Set(prev)
                        if (checked) nextSet.add(m.userId)
                        else nextSet.delete(m.userId)
                        return nextSet
                      })
                    }
                    label={
                      <span className="flex items-center gap-2">
                        <span>{m.name}</span>
                        {m.email && <span className="text-xs text-muted-foreground">{m.email}</span>}
                      </span>
                    }
                  />
                </li>
              ))}
            </ul>
          )}
        </Modal>
      )}
    </div>
  )
}

/**
 * The blocker/warning list and the consent footnote, at the top of the reading column. Renders nothing
 * when there is nothing to fix or note — the header's summary already says it is ready.
 */
export function PublishReadinessCard({ state }: { state: PublishReadinessState }) {
  const { preflight, error } = state
  if (error) return null
  if (!preflight || !preflight.publishing) return null
  if (preflight.blockers.length === 0 && preflight.warnings.length === 0) return null

  return (
    <Card data-testid="publish-readiness">
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
          <p className="text-sm text-muted-foreground">You&rsquo;ve consented to this post as it is now.</p>
        )}
      </CardContent>
    </Card>
  )
}
