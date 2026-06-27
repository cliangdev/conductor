'use client'

import { useEffect, useState } from 'react'
import { apiGet } from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import type { Member, MemberRole } from '@/types'

/**
 * Resolves the signed-in user's role in a project and whether they may mutate
 * (ADMIN/CREATOR). Mirrors the inline role-gating the Settings pages use, shared
 * across the agent pages so write controls hide for REVIEWERs.
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
