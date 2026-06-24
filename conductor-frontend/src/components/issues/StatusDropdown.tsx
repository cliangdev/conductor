'use client'

import { useEffect, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu'
import { apiGet, apiPatch } from '@/lib/api'

type UserRole = 'ADMIN' | 'CREATOR' | 'REVIEWER'

interface StatusDropdownProps {
  projectId: string
  issueId: string
  currentStatus: string
  userRole: UserRole
  token: string
  onStatusChanged: (newStatus: string) => void
}

type StatusVariant =
  | 'status-draft'
  | 'status-review'
  | 'status-approved'
  | 'status-progress'
  | 'status-code-review'
  | 'status-done'
  | 'status-closed'

const STATUS_VARIANTS: Record<string, StatusVariant> = {
  DRAFT: 'status-draft',
  IN_REVIEW: 'status-review',
  READY_FOR_DEVELOPMENT: 'status-approved',
  IN_PROGRESS: 'status-progress',
  CODE_REVIEW: 'status-code-review',
  DONE: 'status-done',
  CLOSED: 'status-closed',
}

const STATUS_LABELS: Record<string, string> = {
  DRAFT: 'Draft',
  IN_REVIEW: 'In Review',
  READY_FOR_DEVELOPMENT: 'Ready for Development',
  IN_PROGRESS: 'In Progress',
  CODE_REVIEW: 'Code Review',
  DONE: 'Done',
  CLOSED: 'Closed',
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

function statusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status.replace(/_/g, ' ')
}

/**
 * COND-18: the doer's status control. The valid next moves are computed server-side from the active
 * Workflow definition (GET .../available-transitions) — not a hardcoded table — so a review-gated
 * transition stays hidden until its Review is satisfied, and the same control works for any Workflow.
 */
export function StatusDropdown({
  projectId,
  issueId,
  currentStatus,
  userRole,
  token,
  onStatusChanged,
}: StatusDropdownProps) {
  const [loading, setLoading] = useState(false)
  const [transitions, setTransitions] = useState<AvailableTransition[]>([])

  const currentVariant = STATUS_VARIANTS[currentStatus] ?? 'status-draft'
  const displayLabel = statusLabel(currentStatus)

  useEffect(() => {
    if (userRole === 'REVIEWER' || !token) return
    let cancelled = false
    apiGet<AvailableTransitionsResponse>(
      `/api/v1/projects/${projectId}/issues/${issueId}/available-transitions`,
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
    return <Badge variant={currentVariant}>{displayLabel}</Badge>
  }

  async function handleSelect(newStatus: string) {
    setLoading(true)
    try {
      await apiPatch(
        `/api/v1/projects/${projectId}/issues/${issueId}`,
        { status: newStatus },
        token
      )
      onStatusChanged(newStatus)
    } catch {
      // Transition rejected (e.g. an unsatisfied gate); UI stays at the current status.
    } finally {
      setLoading(false)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button disabled={loading} className="focus:outline-none">
          <Badge variant={currentVariant} className="cursor-pointer hover:opacity-80 transition-opacity">
            {displayLabel}
            <span className="ml-1 text-xs opacity-60">▼</span>
          </Badge>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {transitions.map((t) => (
          <DropdownMenuItem
            key={t.toStatus}
            onClick={() => handleSelect(t.toStatus)}
            className="cursor-pointer"
          >
            <Badge variant={STATUS_VARIANTS[t.toStatus] ?? 'status-draft'} className="mr-2">
              {t.label || statusLabel(t.toStatus)}
            </Badge>
            {t.requiresReview && <span className="text-xs opacity-60">needs review</span>}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
