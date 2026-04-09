'use client'

import * as React from 'react'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import type { Member, MemberRole } from '@/types'

const ROLE_LABELS: Record<MemberRole, string> = {
  ADMIN: 'Admin',
  CREATOR: 'Creator',
  REVIEWER: 'Reviewer',
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
    <div className="flex items-center justify-between py-3 border-b last:border-b-0">
      <div className="flex items-center gap-3">
        <Avatar className="h-9 w-9">
          <AvatarImage src={member.avatarUrl ?? undefined} alt={member.name} />
          <AvatarFallback>{initials}</AvatarFallback>
        </Avatar>
        <div>
          <p className="text-sm font-medium text-gray-900">
            {member.name}
            {isCurrentUser && <span className="ml-1 text-xs text-gray-400">(you)</span>}
          </p>
          <p className="text-xs text-gray-500">{member.email}</p>
        </div>
      </div>

      <div className="flex items-center gap-3">
        <p className="text-xs text-gray-400 hidden sm:block">Joined {joinedDate}</p>

        {isAdmin && !isCurrentUser ? (
          <select
            value={member.role}
            onChange={(e) => onRoleChange(member.userId, e.target.value as MemberRole)}
            className="text-sm border border-gray-200 rounded-md px-2 py-1 bg-white focus:outline-none focus:ring-2 focus:ring-primary"
            aria-label={`Role for ${member.name}`}
          >
            <option value="ADMIN">Admin</option>
            <option value="CREATOR">Creator</option>
            <option value="REVIEWER">Reviewer</option>
          </select>
        ) : (
          <Badge variant={ROLE_BADGE_VARIANTS[member.role]}>{ROLE_LABELS[member.role]}</Badge>
        )}

        {isAdmin && !isCurrentUser && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onRemove(member.userId, member.name)}
            className="text-red-500 hover:text-red-700 hover:bg-red-50"
            aria-label={`Remove ${member.name}`}
          >
            Remove
          </Button>
        )}
      </div>
    </div>
  )
}
