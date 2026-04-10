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

const STATUS_STYLES: Record<string, string> = {
  DRAFT: 'border-gray-300 bg-gray-100 text-gray-700',
  IN_REVIEW: 'border-blue-300 bg-blue-100 text-blue-700',
  APPROVED: 'border-green-300 bg-green-100 text-green-700',
  CLOSED: 'border-red-300 bg-red-100 text-red-700',
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

  const statusStyle = STATUS_STYLES[currentStatus] ?? STATUS_STYLES.DRAFT
  const displayLabel = currentStatus.replace('_', ' ')

  if (userRole === 'REVIEWER') {
    return (
      <Badge className={`border ${statusStyle}`}>{displayLabel}</Badge>
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
      <Badge className={`border ${statusStyle}`}>{displayLabel}</Badge>
    )
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button disabled={loading} className="focus:outline-none">
          <Badge className={`border ${statusStyle} cursor-pointer hover:opacity-80 transition-opacity`}>
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
            <Badge className={`border ${STATUS_STYLES[status] ?? STATUS_STYLES.DRAFT} mr-2`}>
              {status.replace('_', ' ')}
            </Badge>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
