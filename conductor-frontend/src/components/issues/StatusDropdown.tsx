'use client'

import { useEffect, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { StatusBadge } from '@/components/ui/status-badge'
import { toastError } from '@/components/ui/toast'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu'
import { apiGet, apiPatch, apiErrorMessage } from '@/lib/api'
import {
  DEFAULT_WORKFLOW_SLUG,
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
}: StatusDropdownProps) {
  const [loading, setLoading] = useState(false)
  const [transitions, setTransitions] = useState<AvailableTransition[]>([])

  const view = useWorkflowView(projectId, workflowSlug, token)
  const { label: displayLabel, category } = statusMeta(view, currentStatus)

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

  // REVIEWERs (and any state with no available moves) see a read-only badge.
  if (userRole === 'REVIEWER' || transitions.length === 0) {
    return <StatusBadge status={currentStatus} category={category} label={displayLabel} />
  }

  async function handleSelect(newStatus: string) {
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
        <button disabled={loading} className="inline-flex items-center gap-1 focus:outline-none">
          <StatusBadge
            status={currentStatus}
            category={category}
            label={displayLabel}
            className="cursor-pointer hover:opacity-80 transition-opacity"
          />
          <ChevronDown className="h-3 w-3 opacity-60" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {transitions.map((t) => {
          const meta = statusMeta(view, t.toStatus)
          return (
            <DropdownMenuItem
              key={t.toStatus}
              onClick={() => handleSelect(t.toStatus)}
              className="cursor-pointer"
            >
              <StatusBadge
                status={t.toStatus}
                category={meta.category}
                label={t.label || meta.label}
                className="mr-2"
              />
              {t.requiresReview && <span className="text-xs opacity-60">needs review</span>}
            </DropdownMenuItem>
          )
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
