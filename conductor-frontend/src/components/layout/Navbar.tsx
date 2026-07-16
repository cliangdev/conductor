'use client'

import { MenuIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useSidebar } from '@/contexts/SidebarContext'
import { useCurrentWorkspace } from '@/lib/navigation'

/**
 * Mobile-only top bar (`md:hidden`). Desktop has no navbar — pages own their headers via
 * PageHeader, and the single user menu lives in the Sidebar's UserFooter (reachable here through
 * the hamburger, since the mobile sidebar overlay renders the same footer).
 */
export function Navbar() {
  const { toggleSidebar } = useSidebar()
  const currentWorkspace = useCurrentWorkspace()

  return (
    <nav className="md:hidden flex items-center gap-3 h-12 px-4 border-b border-border bg-background sticky top-0 z-30">
      <Button variant="ghost" size="icon" onClick={toggleSidebar} aria-label="Open menu">
        <MenuIcon className="h-5 w-5" />
      </Button>
      <span className="text-sm font-medium text-foreground truncate">
        {currentWorkspace?.name ?? 'Conductor'}
      </span>
    </nav>
  )
}
