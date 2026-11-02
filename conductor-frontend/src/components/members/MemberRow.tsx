'use client'

import * as React from 'react'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { RoleCheckboxes } from '@/components/members/RoleCheckboxes'
import { humanizeId } from '@/lib/workflows'
import { memberRoles, ROLE_LABELS } from '@/lib/permissions'
import type { Member, MemberRole } from '@/types'

/** Single source of role display labels — reused by the invite modal and the role dropdown. */
export { ROLE_LABELS }

/** Display label for one role, falling back to a humanized id for any role outside the known set. */
function singleRoleLabel(role: MemberRole | string): string {
  return ROLE_LABELS[role as MemberRole] ?? humanizeId(role)
}

/**
 * Display label for a set of roles: the held roles joined with " · ", and "Viewer" for an empty
 * (read-only) set. A bare string is accepted for callers that still hold one legacy role.
 */
export function roleLabel(roles: readonly (MemberRole | string)[] | MemberRole | string | null | undefined): string {
  const list = typeof roles === 'string' ? [roles] : roles ? [...roles] : []
  if (list.length === 0) return 'Viewer'
  const order = ['ADMIN', 'CREATOR', 'REVIEWER']
  const sorted = [...list].sort((a, b) => (order.indexOf(a) + 1 || 99) - (order.indexOf(b) + 1 || 99))
  return sorted.map(singleRoleLabel).join(' · ')
}

interface MemberRowProps {
  member: Member
  isAdmin: boolean
  currentUserId: string
  onRoleChange: (userId: string, roles: MemberRole[]) => void
  onRemove: (userId: string, name: string) => void
}

export function MemberRow({ member, isAdmin, currentUserId, onRoleChange, onRemove }: MemberRowProps) {
  const initials = member.name
    .split(' ')
    .map((n) => n[0])
    .join('')
    .toUpperCase()
    .slice(0, 2)

  const joinedDate = new Date(member.joinedAt).toLocaleDateString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })

  const isCurrentUser = member.userId === currentUserId
  const roles = memberRoles(member)

  return (
    <div className="flex items-center justify-between py-3">
      <div className="flex items-center gap-3">
        <Avatar className="h-9 w-9">
          <AvatarImage src={member.avatarUrl ?? undefined} alt={member.name} />
          <AvatarFallback>{initials}</AvatarFallback>
        </Avatar>
        <div>
          <p className="text-sm font-medium text-foreground">
            {member.name}
            {isCurrentUser && <span className="ml-1 text-xs text-muted-foreground">(you)</span>}
          </p>
          <p className="text-xs text-muted-foreground">{member.email}</p>
        </div>
      </div>

      <div className="flex items-center gap-3">
        <p className="text-xs text-muted-foreground hidden sm:block">Joined {joinedDate}</p>

        {isAdmin && !isCurrentUser ? (
          <RoleCheckboxes
            roles={roles}
            onChange={(next) => onRoleChange(member.userId, next)}
            label={member.name}
            idPrefix={`member-${member.userId}`}
          />
        ) : (
          <Badge variant={roles.includes('ADMIN') ? 'default' : roles.length ? 'secondary' : 'outline'}>
            {roleLabel(roles)}
          </Badge>
        )}

        {isAdmin && !isCurrentUser && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onRemove(member.userId, member.name)}
            className="text-destructive hover:text-destructive hover:bg-destructive/10"
            aria-label={`Remove ${member.name}`}
          >
            Remove
          </Button>
        )}
      </div>
    </div>
  )
}
