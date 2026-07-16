'use client'

import { forwardRef, useState } from 'react'
import { Check } from 'lucide-react'
import { EmptyAvatarSlot, UserAvatar } from '@/components/workitems/UserAvatar'
import { toastError } from '@/components/ui/toast'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { apiPost, apiDelete, apiErrorMessage } from '@/lib/api'
import type { IssueReviewer, Member } from '@/components/workitems/listTypes'
import type { Verdict } from '@/components/reviews/verdict'

// Verdict → ring color, mirroring VerdictIcon's palette (components/reviews/verdict.tsx) so a
// reviewer's avatar ring and its icon elsewhere in the app always agree on what each verdict means.
const VERDICT_RING: Record<Verdict | 'pending', string> = {
  APPROVED: 'ring-status-done',
  CHANGES_REQUESTED: 'ring-status-progress',
  COMMENTED: 'ring-status-review',
  pending: 'ring-border',
}

// Verdict isn't color-only: this text pairs with each avatar's title/aria-label so the ring's
// meaning survives for screen readers and colorblind users too.
const VERDICT_LABEL: Record<Verdict | 'pending', string> = {
  APPROVED: 'Approved',
  CHANGES_REQUESTED: 'Changes requested',
  COMMENTED: 'Commented',
  pending: 'Pending',
}

/**
 * Reviewer avatar stack for a Work Item row: overlapping avatars, each ringed in its verdict's color.
 * Trigger opens the same add/remove reviewer menu as before (COND-22 list); only the trigger's visual
 * changed from a badge to a stack. `ref` is forwarded so keyboard nav has a button to open.
 */
export const ReviewerCell = forwardRef<
  HTMLButtonElement,
  {
    issueId: string
    projectId: string
    reviewers: IssueReviewer[]
    members: Member[]
    token: string
    onChanged: (reviewers: IssueReviewer[]) => void
  }
>(function ReviewerCell({ issueId, projectId, reviewers, members, token, onChanged }, ref) {
  const [saving, setSaving] = useState<string | null>(null)

  const reviewerMembers = members.filter((m) => m.role === 'REVIEWER')
  const assignedIds = new Set(reviewers.map((r) => r.userId))

  async function toggleReviewer(member: Member) {
    setSaving(member.userId)
    try {
      if (assignedIds.has(member.userId)) {
        await apiDelete(`/api/v2/projects/${projectId}/work-items/${issueId}/reviewers/${member.userId}`, token)
        onChanged(reviewers.filter((r) => r.userId !== member.userId))
      } else {
        await apiPost(`/api/v2/projects/${projectId}/work-items/${issueId}/reviewers`, { userId: member.userId }, token)
        onChanged([...reviewers, { userId: member.userId, name: member.name, avatarUrl: member.avatarUrl ?? undefined }])
      }
    } catch (err) {
      toastError(apiErrorMessage(err, assignedIds.has(member.userId) ? 'Failed to remove reviewer' : 'Failed to add reviewer'))
    } finally {
      setSaving(null)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          ref={ref}
          aria-label={reviewers.length === 0 ? 'Add reviewers' : `Reviewers: ${reviewers.map((r) => r.name).join(', ')}`}
          className="flex items-center focus:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-full shrink-0"
        >
          {reviewers.length === 0 ? (
            <EmptyAvatarSlot size={5} className="hover:border-border-strong transition-colors" />
          ) : (
            reviewers.map((r, i) => (
              <UserAvatar
                key={r.userId}
                name={r.name}
                avatarUrl={r.avatarUrl}
                size={5}
                label={`${r.name} — ${VERDICT_LABEL[r.reviewVerdict ?? 'pending']}`}
                className={`ring-2 ${VERDICT_RING[r.reviewVerdict ?? 'pending']} ${i > 0 ? '-ml-1.5' : ''}`}
              />
            ))
          )}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-52">
        <DropdownMenuLabel className="text-xs text-muted-foreground font-medium">Reviewers</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {reviewerMembers.length === 0 ? (
          <DropdownMenuItem disabled>No reviewer-role members</DropdownMenuItem>
        ) : (
          reviewerMembers.map((m) => {
            const assigned = assignedIds.has(m.userId)
            const isSaving = saving === m.userId
            return (
              <DropdownMenuItem key={m.userId} disabled={isSaving} onClick={() => toggleReviewer(m)} className="cursor-pointer gap-2">
                <span className="w-4 shrink-0 flex items-center justify-center">
                  {assigned && <Check className="h-3.5 w-3.5" />}
                </span>
                <UserAvatar name={m.name} avatarUrl={m.avatarUrl} size={5} />
                <span className="truncate flex-1">{m.name}</span>
              </DropdownMenuItem>
            )
          })
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
})
