'use client'

import type { ReactNode, RefObject } from 'react'
import { StatusBadge } from '@/components/ui/status-badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { FloatingBulkBar } from '@/components/marketing/shared/FloatingBulkBar'
import { UserAvatar } from '@/components/workitems/UserAvatar'
import type { Member } from '@/components/workitems/listTypes'

/**
 * Appears when ≥1 row is selected. Both actions act on the whole selection (sequential mutations,
 * one toastError on any failure — see WorkItemListView). The trigger refs let keyboard nav (the
 * command palette's "Selection" group) open these same menus programmatically instead of duplicating
 * the status/member lists a second time.
 */
export function BulkActionBar({
  count,
  statusOptions,
  members,
  onChangeStatus,
  onAssign,
  disabled,
  statusTriggerRef,
  assignTriggerRef,
  onClear,
  hiddenCount,
  children,
}: {
  count: number
  statusOptions: { id: string; label: string; category: string }[]
  members: Member[]
  onChangeStatus: (statusId: string) => void
  onAssign: (member: Member | null) => void
  /** True while a bulk mutation is in flight — disables both triggers so a second one can't be submitted before the first settles. */
  disabled?: boolean
  statusTriggerRef?: RefObject<HTMLButtonElement | null>
  assignTriggerRef?: RefObject<HTMLButtonElement | null>
  onClear?: () => void
  /** Selected but hidden by a search, filter or tab: kept, but never acted on. */
  hiddenCount?: number
  /** Extra actions after Assign (the Posts-only bulk actions). */
  children?: ReactNode
}) {
  return (
    <FloatingBulkBar count={count} hiddenCount={hiddenCount} onClear={onClear}>
      <div className="flex items-center gap-2">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button ref={statusTriggerRef} variant="outline" size="sm" disabled={disabled}>
              Change status
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {statusOptions.map((s) => (
              <DropdownMenuItem key={s.id} className="cursor-pointer" onClick={() => onChangeStatus(s.id)}>
                <StatusBadge status={s.id} category={s.category} label={s.label} />
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>

        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button ref={assignTriggerRef} variant="outline" size="sm" disabled={disabled}>
              Assign
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-52">
            <DropdownMenuItem className="cursor-pointer text-muted-foreground" onClick={() => onAssign(null)}>
              Unassign
            </DropdownMenuItem>
            {members.map((m) => (
              <DropdownMenuItem key={m.userId} className="cursor-pointer gap-2" onClick={() => onAssign(m)}>
                <UserAvatar name={m.name} avatarUrl={m.avatarUrl} size={5} />
                <span className="truncate flex-1">{m.name}</span>
              </DropdownMenuItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
        {children}
      </div>
    </FloatingBulkBar>
  )
}
