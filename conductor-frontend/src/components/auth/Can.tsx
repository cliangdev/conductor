'use client'

import type { ReactNode } from 'react'
import { usePermissions } from '@/contexts/PermissionsContext'
import type { Capability } from '@/lib/permissions'

/**
 * Conditionally renders children based on a capability.
 *
 * Renders `children` only when the role is resolved AND holds the capability; renders
 * `fallback` when resolved but denied; renders nothing while the role is still loading
 * (prevents a read-only/disabled state from flashing for users who actually have access).
 */
export function Can({
  do: capability,
  fallback = null,
  children,
}: {
  do: Capability
  fallback?: ReactNode
  children: ReactNode
}) {
  const { can, loading } = usePermissions()
  if (loading) return null
  return <>{can(capability) ? children : fallback}</>
}
