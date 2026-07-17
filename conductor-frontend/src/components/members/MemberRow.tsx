'use client'

import * as React from 'react'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/select'
import { humanizeId } from '@/lib/workflows'
import type { Member, MemberRole } from '@/types'

/** Single source of role display labels — reused by the invite modal and the role dropdown. */
export const ROLE_LABELS: Record<MemberRole, string> = {
  ADMIN: 'Admin',
  CREATOR: 'Creator',
  REVIEWER: 'Reviewer',
}

/** Display label for a role, falling back to a humanized id for any role outside the known set. */
export function roleLabel(role: MemberRole | string): string {
  return ROLE_LABELS[role as MemberRole] ?? humanizeId(role)
}

const ROLE_BADGE_VARIANTS: Record<MemberRole, 'default' | 'secondary' | 'outline'> = {
  ADMIN: 'default',
  CREATOR: 'secondary',
  REVIEWER: 'outline',
}

interface MemberRowProps {
  member: Member
  isAdmin: boolean
  currentUserId: string
  onRoleChange: (userId: string, role: MemberRole) => void
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
          <Select
            value={member.role}
            onChange={(e) => onRoleChange(member.userId, e.target.value as MemberRole)}
            className="w-auto"
            aria-label={`Role for ${member.name}`}
          >
            {(Object.keys(ROLE_LABELS) as MemberRole[]).map((role) => (
              <option key={role} value={role}>
                {roleLabel(role)}
              </option>
            ))}
          </Select>
        ) : (
          <Badge variant={ROLE_BADGE_VARIANTS[member.role] ?? 'outline'}>{roleLabel(member.role)}</Badge>
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
