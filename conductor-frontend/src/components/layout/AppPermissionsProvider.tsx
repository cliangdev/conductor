'use client'

import type { ReactNode } from 'react'
import { PermissionsProvider } from '@/contexts/PermissionsContext'
import { useCurrentWorkspace } from '@/lib/navigation'

/**
 * Mounts PermissionsProvider at the app shell level (`app/layout.tsx`), resolving "the current
 * workspace" the same way the Sidebar and CommandPalette already do (`useCurrentWorkspace`).
 * Those two render as siblings of the routed page tree — not descendants of
 * `[projectId]/layout.tsx` — so they need permission checks (issue #290: gate admin-flavored nav
 * by capability) from a provider that wraps them too, not just the project pages.
 */
export function AppPermissionsProvider({ children }: { children: ReactNode }) {
  const currentWorkspace = useCurrentWorkspace()
  return <PermissionsProvider projectId={currentWorkspace?.id ?? ''}>{children}</PermissionsProvider>
}
