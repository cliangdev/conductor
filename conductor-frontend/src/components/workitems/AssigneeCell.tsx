'use client'

import { forwardRef, useState } from 'react'
import { Check } from 'lucide-react'
import { EmptyAvatarSlot, UserAvatar } from '@/components/workitems/UserAvatar'
import { toastError } from '@/components/ui/toast'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { apiPatch, apiErrorMessage } from '@/lib/api'
import type { IssueAssignee, Member } from '@/components/workitems/listTypes'

/**
 * Assignee dropdown for a Work Item row. The trigger is just the avatar (or an "Unassigned" glyph) —
 * no badge chrome — so it drops cleanly into the row's right-aligned meta cluster. `ref` is forwarded
 * to the trigger button so keyboard nav (the "A" shortcut) can open it programmatically.
 */
export const AssigneeCell = forwardRef<
  HTMLButtonElement,
  {
    issueId: string
    projectId: string
    assignee?: IssueAssignee | null
    members: Member[]
    token: string
    onChanged: (assignee: IssueAssignee | null) => void
  }
>(function AssigneeCell({ issueId, projectId, assignee, members, token, onChanged }, ref) {
  const [saving, setSaving] = useState(false)

  async function handleSelect(member: Member | null) {
    setSaving(true)
    try {
      await apiPatch(
        `/api/v2/projects/${projectId}/work-items/${issueId}`,
        { assigneeId: member ? member.userId : '' },
        token
      )
      onChanged(member ? { userId: member.userId, name: member.name, avatarUrl: member.avatarUrl } : null)
    } catch (err) {
      toastError(apiErrorMessage(err, 'Failed to update assignee'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          ref={ref}
          disabled={saving}
          aria-label={assignee ? `Reassign, currently ${assignee.name}` : 'Assign'}
          className="focus:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-full shrink-0"
        >
          {assignee ? (
            <UserAvatar name={assignee.name} avatarUrl={assignee.avatarUrl} size={5} className="hover:opacity-80 transition-opacity" />
          ) : (
            <EmptyAvatarSlot size={5} className="hover:border-border-strong transition-colors" />
          )}
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-52">
        {assignee && (
          <>
            <DropdownMenuItem
              onClick={() => handleSelect(null)}
              className="cursor-pointer text-destructive focus:text-destructive"
            >
              Unassign
            </DropdownMenuItem>
            <DropdownMenuSeparator />
          </>
        )}
        {members.map((m) => (
          <DropdownMenuItem key={m.userId} onClick={() => handleSelect(m)} className="cursor-pointer gap-2">
            <UserAvatar name={m.name} avatarUrl={m.avatarUrl} size={5} />
            <span className="truncate flex-1">{m.name}</span>
            {assignee?.userId === m.userId && <Check className="h-3.5 w-3.5 text-primary" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
})
