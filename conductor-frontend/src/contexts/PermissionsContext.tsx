'use client'

import { createContext, useContext, useEffect, useState, useCallback, ReactNode } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { can as canFn, memberRoles, type Capability } from '@/lib/permissions'
import { fetchMembersFresh, getMembersCacheEntry, invalidateMembersCache } from '@/lib/workflows'
import type { MemberRole } from '@/types'

interface PermissionsContextValue {
  /** The roles the signed-in user holds in the active project; empty until resolved / for a read-only Viewer. */
  roles: MemberRole[]
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
  const cachedMember = getMembersCacheEntry(projectId)?.find((m) => m.userId === user?.id)
  const [roles, setRoles] = useState<MemberRole[]>(() => memberRoles(cachedMember))
  const [loading, setLoading] = useState(!cachedMember)

  useEffect(() => {
    if (!accessToken || !projectId) {
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    // Always ask the server. The cached list seeded the role above so nothing flashes, but a role
    // granted since that list was stored (from another browser, or by another admin) would otherwise
    // never reach this browser: the stored list has no expiry and nothing else refreshes it.
    fetchMembersFresh(projectId, accessToken)
      .then((members) => {
        if (!cancelled) setRoles(memberRoles(members.find((m) => m.userId === user?.id)))
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

  const can = useCallback((capability: Capability) => canFn(roles, capability), [roles])
  // Invalidate the shared cache before re-fetching so the role update is guaranteed fresh.
  const refresh = useCallback(() => {
    invalidateMembersCache(projectId)
    setReloadKey((k) => k + 1)
  }, [projectId])

  return (
    <PermissionsContext.Provider value={{ roles, loading, can, refresh }}>
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
