'use client'

import { useEffect, useState } from 'react'
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
import { apiGet, apiPatch, apiErrorMessage } from '@/lib/api'
import { useTikTokPublishGate } from '@/components/marketing/TikTokConsentStep'
import {
  DEFAULT_WORKFLOW_SLUG,
  statusHasReviewGate,
  statusMeta,
  useWorkflowView,
} from '@/lib/workflows'

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
}

interface AvailableTransition {
  toStatus: string
  label: string
  requiresReview?: boolean
}

interface AvailableTransitionsResponse {
  workflow: string
  currentStatus: string
  noun?: string
  transitions: AvailableTransition[]
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
}: StatusDropdownProps) {
  const [loading, setLoading] = useState(false)
  const [transitions, setTransitions] = useState<AvailableTransition[]>([])

  const view = useWorkflowView(projectId, workflowSlug, token)
  const { label: displayLabel, category } = statusMeta(view, currentStatus)

  // TIK-4: why a Post carrying TikTok targets can't be sent for approval yet — null for everything
  // else, so no other Work Item sees a difference. Published by TikTokConsentStep's provider.
  const tiktokBlock = useTikTokPublishGate()

  /** A move into a review-gated status is the submit-for-approval move the consent gate guards. */
  function blockedReason(toStatus: string): string | null {
    return tiktokBlock && statusHasReviewGate(view, toStatus) ? tiktokBlock : null
  }

  useEffect(() => {
    if (userRole === 'REVIEWER' || !token) return
    let cancelled = false
    apiGet<AvailableTransitionsResponse>(
      `/api/v2/projects/${projectId}/work-items/${issueId}/available-transitions`,
      token
    )
      .then((res) => {
        if (!cancelled) setTransitions(res.transitions ?? [])
      })
      .catch(() => {
        if (!cancelled) setTransitions([])
      })
    return () => {
      cancelled = true
    }
  }, [projectId, issueId, currentStatus, userRole, token])

  // REVIEWERs (and any state with no available moves) see a read-only indicator.
  if (userRole === 'REVIEWER' || transitions.length === 0) {
    return trigger === 'ring' ? (
      <StatusRing status={currentStatus} category={category} label={displayLabel} />
    ) : (
      <StatusBadge status={currentStatus} category={category} label={displayLabel} />
    )
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
