'use client'

import { createContext, useContext, useEffect, useState, useCallback, ReactNode } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { can as canFn, type Capability } from '@/lib/permissions'
import { fetchMembersCached, getMembersCacheEntry, invalidateMembersCache } from '@/lib/workflows'
import type { MemberRole } from '@/types'

interface PermissionsContextValue {
  /** The signed-in user's role in the active project, or undefined until resolved / if none. */
  role: MemberRole | undefined
  /** True while the role is still being fetched — gate write UI on `!loading` to avoid flashes. */
  loading: boolean
  /** Whether the current role holds a capability. */
  can: (capability: Capability) => boolean
  /** Re-fetch membership (e.g. after a role change). */
  refresh: () => void
}

const PermissionsContext = createContext<PermissionsContextValue | null>(null)

/**
 * Resolves the signed-in user's role for one project, once, and exposes capability checks.
 * Centralized here so role resolution and the permission rule live in a single place instead
 * of being re-fetched and re-derived in every page. Mounted per-project in the project layout.
 */
export function PermissionsProvider({ projectId, children }: { projectId: string; children: ReactNode }) {
  const { accessToken, user } = useAuth()
  const [reloadKey, setReloadKey] = useState(0)

  // Seed role synchronously from the members cache (localStorage → module cache) so that
  // capability checks don't flash as "no permission" on revisit.
  const cachedRole = getMembersCacheEntry(projectId)?.find((m) => m.userId === user?.id)?.role
  const [role, setRole] = useState<MemberRole | undefined>(cachedRole)
  const [loading, setLoading] = useState(!cachedRole)

  useEffect(() => {
    if (!accessToken || !projectId) {
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    fetchMembersCached(projectId, accessToken)
      .then((members) => {
        if (!cancelled) setRole(members.find((m) => m.userId === user?.id)?.role)
      })
      .catch(() => {
        /* non-fatal — treat as no role */
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken, user?.id, reloadKey])

  const can = useCallback((capability: Capability) => canFn(role, capability), [role])
  // Invalidate the shared cache before re-fetching so the role update is guaranteed fresh.
  const refresh = useCallback(() => {
    invalidateMembersCache(projectId)
    setReloadKey((k) => k + 1)
  }, [projectId])

  return (
    <PermissionsContext.Provider value={{ role, loading, can, refresh }}>
      {children}
    </PermissionsContext.Provider>
  )
}

export function usePermissions(): PermissionsContextValue {
  const ctx = useContext(PermissionsContext)
  if (!ctx) throw new Error('usePermissions must be used within PermissionsProvider')
  return ctx
}

/** Convenience: whether the current role holds a capability. */
export function useCan(capability: Capability): boolean {
  return usePermissions().can(capability)
}
