'use client'

// The header's secondary moves: every transition the Workflow allows from here that is not the page's
// one primary button and not a reviewer's verdict, then anything the page adds, then Delete. It reads
// the same available-transitions and the same blocked-move reasons the rail's status control does, so
// the two never disagree about what a person may do next.

import { useState } from 'react'
import { MoreHorizontal } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { StatusBadge } from '@/components/ui/status-badge'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiPatch } from '@/lib/api'
import { statusMeta } from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'
import { useAvailableTransitions, useBlockedReason, type UserRole } from '@/components/issues/useAvailableTransitions'

export interface WorkItemMoreMenuProps {
  projectId: string
  issueId: string
  currentStatus: string
  userRole: UserRole
  token: string
  workflowView?: WorkflowView
  /** Moves the publish gate would refuse right now, keyed by target status, with the gate's reason. */
  blockedMoves?: Record<string, string>
  /** The move the primary button already offers; not repeated here. */
  excludeStatus?: string | null
  onStatusChanged: (newStatus: string) => void
  /** Offered last, in red, to anyone who may delete the item. The confirmation is the caller's. */
  onDelete?: () => void
  /** Anything the page adds between the moves and Delete, as DropdownMenuItems. */
  extraItems?: React.ReactNode
  noun?: string
}

export function WorkItemMoreMenu({
  projectId,
  issueId,
  currentStatus,
  userRole,
  token,
  workflowView,
  blockedMoves,
  excludeStatus,
  onStatusChanged,
  onDelete,
  extraItems,
  noun = 'item',
}: WorkItemMoreMenuProps) {
  const [busy, setBusy] = useState(false)
  const transitions = useAvailableTransitions(projectId, issueId, currentStatus, userRole, token)
  const blockedReason = useBlockedReason(workflowView, blockedMoves)

  // A review-gated move is a reviewer's verdict, offered as Approve beside this menu, never here.
  const moves = transitions.filter((t) => t.toStatus !== excludeStatus && !t.requiresReview)
  if (moves.length === 0 && !extraItems && !onDelete) return null

  async function move(toStatus: string) {
    const blocked = blockedReason(toStatus)
    if (blocked) {
      toastError(blocked)
      return
    }
    setBusy(true)
    try {
      await apiPatch(`/api/v2/projects/${projectId}/work-items/${issueId}`, { status: toStatus }, token)
      onStatusChanged(toStatus)
    } catch (err) {
      toastError(apiErrorMessage(err, 'Failed to update status'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="sm" className="px-2" aria-label="More actions" disabled={busy}>
          <MoreHorizontal className="h-4 w-4" aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {moves.map((t) => {
          const meta = statusMeta(workflowView, t.toStatus)
          const blocked = blockedReason(t.toStatus)
          return (
            <DropdownMenuItem
              key={t.toStatus}
              disabled={!!blocked}
              title={blocked ?? undefined}
              onClick={() => void move(t.toStatus)}
              className={blocked ? 'max-w-xs items-start whitespace-normal' : 'cursor-pointer'}
            >
              <StatusBadge status={t.toStatus} category={meta.category} label={t.label || meta.label} className="mr-2" />
              {blocked && <span className="text-xs text-muted-foreground">{blocked}</span>}
            </DropdownMenuItem>
          )
        })}
        {extraItems && moves.length > 0 && <DropdownMenuSeparator />}
        {extraItems}
        {onDelete && (
          <>
            {(moves.length > 0 || extraItems) && <DropdownMenuSeparator />}
            <DropdownMenuItem onClick={onDelete} className="cursor-pointer text-destructive focus:text-destructive">
              Delete this {noun.toLowerCase()}
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
