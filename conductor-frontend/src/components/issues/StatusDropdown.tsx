'use client'

import { useState } from 'react'
import { Badge } from '@/components/ui/badge'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu'
import { apiPatch } from '@/lib/api'

type Status = 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'CLOSED'
type UserRole = 'ADMIN' | 'CREATOR' | 'REVIEWER'

interface StatusDropdownProps {
  projectId: string
  issueId: string
  currentStatus: string
  userRole: UserRole
  token: string
  onStatusChanged: (newStatus: string) => void
}

type StatusVariant = 'status-draft' | 'status-review' | 'status-approved' | 'status-closed'

const STATUS_VARIANTS: Record<string, StatusVariant> = {
  DRAFT: 'status-draft',
  IN_REVIEW: 'status-review',
  APPROVED: 'status-approved',
  CLOSED: 'status-closed',
}

const VALID_TRANSITIONS: Record<string, Status[]> = {
  DRAFT: ['IN_REVIEW'],
  IN_REVIEW: ['DRAFT', 'APPROVED', 'CLOSED'],
  APPROVED: ['CLOSED', 'IN_REVIEW'],
  CLOSED: [],
}

export function StatusDropdown({
  projectId,
  issueId,
  currentStatus,
  userRole,
  token,
  onStatusChanged,
}: StatusDropdownProps) {
  const [loading, setLoading] = useState(false)

  const currentVariant = STATUS_VARIANTS[currentStatus] ?? 'status-draft'
  const displayLabel = currentStatus.replace('_', ' ')

  if (userRole === 'REVIEWER') {
    return (
      <Badge variant={currentVariant}>{displayLabel}</Badge>
    )
  }

  const transitions = VALID_TRANSITIONS[currentStatus] ?? []

  async function handleSelect(newStatus: Status) {
    setLoading(true)
    try {
      await apiPatch(
        `/api/v1/projects/${projectId}/issues/${issueId}`,
        { status: newStatus },
        token
      )
      onStatusChanged(newStatus)
    } catch {
      // Status change failed silently; UI stays at current status
    } finally {
      setLoading(false)
    }
  }

  if (transitions.length === 0) {
    return (
      <Badge variant={currentVariant}>{displayLabel}</Badge>
    )
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
        {transitions.map((status) => (
          <DropdownMenuItem
            key={status}
            onClick={() => handleSelect(status)}
            className="cursor-pointer"
          >
            <Badge variant={STATUS_VARIANTS[status] ?? 'status-draft'} className="mr-2">
              {status.replace('_', ' ')}
            </Badge>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
