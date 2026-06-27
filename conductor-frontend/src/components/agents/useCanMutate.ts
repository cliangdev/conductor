'use client'

import { useEffect, useState } from 'react'
import { apiGet } from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import type { Member, MemberRole } from '@/types'

/**
 * Resolves the signed-in user's role in a project and whether they may mutate.
 *
 * Agents are authored content that bind integration tools and run inside workflows, so the write
 * gate is ADMIN||CREATOR — deliberately matching Integrations and Workflows (its closest analogs),
 * not the ADMIN-only gate used by members/notifications/general. REVIEWERs get read-only browse.
 * Centralized here so the rule (and any future change) lives in one place.
 */
export function useCanMutate(projectId: string): { role: MemberRole | undefined; canMutate: boolean } {
  const { accessToken, user } = useAuth()
  const [members, setMembers] = useState<Member[]>([])

  useEffect(() => {
    if (!accessToken || !projectId) return
    apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, accessToken)
      .then(setMembers)
      .catch(() => {
        /* non-fatal — treat as no role */
      })
  }, [projectId, accessToken])

  const role = members.find((m) => m.userId === user?.id)?.role
  return { role, canMutate: role === 'ADMIN' || role === 'CREATOR' }
}
