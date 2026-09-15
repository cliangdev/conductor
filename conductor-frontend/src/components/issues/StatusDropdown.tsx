'use client'

import { useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { StatusBadge } from '@/components/ui/status-badge'
import { StatusRing } from '@/components/workitems/StatusRing'
import { toastError } from '@/components/ui/toast'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu'
import { apiPatch, apiErrorMessage } from '@/lib/api'
import { useAvailableTransitions, useBlockedReason } from './useAvailableTransitions'
import { DEFAULT_WORKFLOW_SLUG, statusMeta, useWorkflowView } from '@/lib/workflows'

type UserRole = 'ADMIN' | 'CREATOR' | 'REVIEWER'

interface StatusDropdownProps {
  projectId: string
  issueId: string
  currentStatus: string
  userRole: UserRole
  token: string
  onStatusChanged: (newStatus: string) => void
  /** Workflow the Work Item is bound to; defaults to the project's Engineering Workflow. */
  workflowSlug?: string
  /**
   * Trigger visual: the default status pill/chevron everywhere, or a bare 14px `StatusRing` for the
   * list row (COND-22 redesign). Same fetch/mutation logic either way — only the trigger markup differs.
   */
  trigger?: 'badge' | 'ring'
  /** Forwarded to the trigger `<button>` so keyboard nav (list "S" shortcut, bulk-bar palette action) can open it programmatically. No-op on the read-only (REVIEWER / no transitions) branch. */
  triggerRef?: React.Ref<HTMLButtonElement>
  /**
   * The review-gated move out of the current status, offered to an assigned reviewer as their own
   * verdict. The server withholds a gated transition from available-transitions until a review
   * satisfies it, so without this a reviewer opening the menu saw every move but the one that is
   * theirs to make. Choosing it records an approval; the Workflow then takes the edge itself.
   */
  reviewVerdict?: ReviewVerdictOption
  /**
   * Moves the publish gate would refuse right now, keyed by target status, each with the gate's own
   * reason. The server's available-transitions is structural — what the Workflow allows from here —
   * while the gate's checks (fire time, media, consent) run at move time; without this the menu
   * offered a move the readiness card beside it was saying is blocked.
   */
  blockedMoves?: Record<string, string>
}

export interface ReviewVerdictOption {
  toStatus: string
  /** What the menu says, e.g. "Approve". */
  label: string
  /** Records the approval (and whatever the Workflow does with it). Rejections surface as a toast. */
  submit: () => Promise<void>
}

/**
 * COND-18: the doer's status control. The current status's label + color come from the bound
 * Workflow's view (single source — see lib/workflows), and the valid next moves are computed
 * server-side from the active Workflow definition (GET .../available-transitions) — not a hardcoded
 * table — so a review-gated transition stays hidden until its Review is satisfied, and the same
 * control works for any Workflow.
 */
export function StatusDropdown({
  projectId,
  issueId,
  currentStatus,
  userRole,
  token,
  onStatusChanged,
  workflowSlug = DEFAULT_WORKFLOW_SLUG,
  trigger = 'badge',
  triggerRef,
  reviewVerdict,
  blockedMoves,
}: StatusDropdownProps) {
  const [loading, setLoading] = useState(false)

  const view = useWorkflowView(projectId, workflowSlug, token)
  const { label: displayLabel, category } = statusMeta(view, currentStatus)

  const transitions = useAvailableTransitions(projectId, issueId, currentStatus, userRole, token)
  // TIK-4 and the publish gate: the one reason a move is refused, shared with every other status
  // control on the page so they all disable the same move for the same reason.
  const blockedReason = useBlockedReason(view, blockedMoves)

  // REVIEWERs (and any state with no available moves) see a read-only indicator — unless there is a
  // verdict of theirs to give, which is the one move a reviewer owns.
  if ((userRole === 'REVIEWER' || transitions.length === 0) && !reviewVerdict) {
    return trigger === 'ring' ? (
      <StatusRing status={currentStatus} category={category} label={displayLabel} />
    ) : (
      <StatusBadge status={currentStatus} category={category} label={displayLabel} />
    )
  }

  async function handleVerdict() {
    if (!reviewVerdict) return
    setLoading(true)
    try {
      await reviewVerdict.submit()
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not record your approval'))
    } finally {
      setLoading(false)
    }
  }

  async function handleSelect(newStatus: string) {
    // A Post going to TikTok can't enter a review-gated status until the creator has consented.
    const blocked = blockedReason(newStatus)
    if (blocked) {
      toastError(blocked)
      return
    }
    setLoading(true)
    try {
      await apiPatch(
        `/api/v2/projects/${projectId}/work-items/${issueId}`,
        { status: newStatus },
        token
      )
      onStatusChanged(newStatus)
    } catch (err) {
      // Transition rejected (e.g. an unsatisfied gate); UI stays at the current status.
      toastError(apiErrorMessage(err, 'Failed to update status'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        {trigger === 'ring' ? (
          <button
            ref={triggerRef}
            disabled={loading}
            aria-label={`Change status (currently ${displayLabel})`}
            className="inline-flex focus:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-full"
          >
            <StatusRing status={currentStatus} category={category} className="cursor-pointer hover:opacity-80 transition-opacity" />
          </button>
        ) : (
          <button ref={triggerRef} disabled={loading} className="inline-flex items-center gap-1 focus:outline-none">
            <StatusBadge
              status={currentStatus}
              category={category}
              label={displayLabel}
              className="cursor-pointer hover:opacity-80 transition-opacity"
            />
            <ChevronDown className="h-3 w-3 opacity-60" />
          </button>
        )}
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {reviewVerdict && (
          <DropdownMenuItem
            key={`verdict-${reviewVerdict.toStatus}`}
            onClick={() => void handleVerdict()}
            className="cursor-pointer"
          >
            <StatusBadge
              status={reviewVerdict.toStatus}
              category={statusMeta(view, reviewVerdict.toStatus).category}
              label={reviewVerdict.label}
              className="mr-2"
            />
            <span className="text-xs opacity-60">your approval</span>
          </DropdownMenuItem>
        )}
        {transitions.map((t) => {
          const meta = statusMeta(view, t.toStatus)
          const blocked = blockedReason(t.toStatus)
          return (
            <DropdownMenuItem
              key={t.toStatus}
              disabled={!!blocked}
              title={blocked ?? undefined}
              onClick={() => handleSelect(t.toStatus)}
              className={
                blocked
                  ? 'max-w-xs items-start whitespace-normal'
                  : 'cursor-pointer'
              }
            >
              <StatusBadge
                status={t.toStatus}
                category={meta.category}
                label={t.label || meta.label}
                className="mr-2"
              />
              {blocked ? (
                <span className="text-xs text-muted-foreground">{blocked}</span>
              ) : (
                t.requiresReview && <span className="text-xs opacity-60">needs review</span>
              )}
            </DropdownMenuItem>
          )
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
