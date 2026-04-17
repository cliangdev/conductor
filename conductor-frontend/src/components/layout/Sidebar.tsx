'use client'

import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import {
  BellIcon,
  CheckIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  EyeIcon,
  FileTextIcon,
  FolderIcon,
  GitBranchIcon,
  GitForkIcon,
  KeyIcon,
  LogOutIcon,
  MoreHorizontalIcon,
  PlusIcon,
  SettingsIcon,
  UsersIcon,
  UsersRoundIcon,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { CreateOrgDialog } from '@/components/layout/CreateOrgDialog'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useSidebar } from '@/contexts/SidebarContext'
import { useProject } from '@/contexts/ProjectContext'
import { useOrg } from '@/contexts/OrgContext'
import { useAuth } from '@/contexts/AuthContext'
import { cn } from '@/lib/utils'
import type { Project } from '@/types'

// ─── Primitives ───────────────────────────────────────────────────────────────

function SidebarSectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="px-3 pt-4 pb-1 text-xs font-semibold text-muted-foreground tracking-wide select-none truncate">
      {children}
    </p>
  )
}

function NavItem({
  href,
  icon,
  children,
  onNavigate,
  exact = false,
}: {
  href: string
  icon?: React.ReactNode
  children: React.ReactNode
  onNavigate?: () => void
  exact?: boolean
}) {
  const pathname = usePathname()
  const isActive = exact ? pathname === href : pathname.startsWith(href)
  return (
    <Link
      href={href}
      onClick={onNavigate}
      className={cn(
        'flex items-center gap-3 px-3 py-2 rounded-md text-sm transition-colors',
        isActive
          ? 'bg-sidebar-active text-sidebar-active-text font-medium'
          : 'text-foreground hover:bg-sidebar-hover'
      )}
    >
      {icon && <span className="h-4 w-4 shrink-0 opacity-70">{icon}</span>}
      <span className="truncate flex-1">{children}</span>
    </Link>
  )
}

function NavGroup({
  href,
  icon,
  label,
  subLinks,
  onNavigate,
}: {
  href: string
  icon?: React.ReactNode
  label: string
  subLinks: React.ReactNode
  onNavigate?: () => void
}) {
  const pathname = usePathname()
  const isActive = pathname.startsWith(href)
  const [expanded, setExpanded] = useState(isActive)

  useEffect(() => {
    if (isActive) setExpanded(true)
  }, [isActive])

  return (
    <div>
      <button
        onClick={() => setExpanded((e) => !e)}
        className={cn(
          'flex items-center gap-3 px-3 py-2 rounded-md text-sm w-full transition-colors',
          isActive
            ? 'bg-sidebar-active text-sidebar-active-text font-medium'
            : 'text-foreground hover:bg-sidebar-hover'
        )}
      >
        {icon && <span className="h-4 w-4 shrink-0 opacity-70">{icon}</span>}
        <span className="flex-1 truncate text-left">{label}</span>
        <ChevronRightIcon className={cn('h-3 w-3 transition-transform shrink-0 opacity-50', expanded && 'rotate-90')} />
      </button>
      {expanded && (
        <div className="ml-7 mt-0.5 space-y-0.5 border-l border-sidebar-border pl-2">
          {subLinks}
        </div>
      )}
    </div>
  )
}

// ─── Project Switcher ─────────────────────────────────────────────────────────

function ProjectSwitcher({
  projects,
  currentProject,
  onNavigate,
}: {
  projects: Project[]
  currentProject: Project | null
  onNavigate?: () => void
}) {
  const router = useRouter()
  const { setActiveProject } = useProject()

  function selectProject(project: Project) {
    setActiveProject(project)
    router.push(`/app/projects/${project.id}/issues`)
    onNavigate?.()
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button className="w-full flex items-center gap-2.5 px-3 py-2.5 hover:bg-sidebar-hover transition-colors text-left group">
          <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded bg-primary/15 text-primary">
            <FolderIcon className="h-3.5 w-3.5" />
          </span>
          <span className="flex-1 truncate text-sm font-semibold text-foreground">
            {currentProject ? currentProject.name : 'Select a project'}
          </span>
          <ChevronDownIcon className="h-3.5 w-3.5 text-muted-foreground shrink-0 opacity-60 group-hover:opacity-100 transition-opacity" />
        </button>
      </DropdownMenuTrigger>

      <DropdownMenuContent align="start" className="w-56">
        {projects.length > 0 && (
          <>
            <DropdownMenuLabel className="text-xs text-muted-foreground font-normal">
              Switch project
            </DropdownMenuLabel>
            <div className="max-h-52 overflow-y-auto">
              {projects.map((project) => (
                <DropdownMenuItem
                  key={project.id}
                  onSelect={() => selectProject(project)}
                  className="flex items-center gap-2"
                >
                  {currentProject?.id === project.id
                    ? <CheckIcon className="h-3.5 w-3.5 shrink-0" />
                    : <span className="h-3.5 w-3.5 shrink-0" />
                  }
                  <span className="truncate">{project.name}</span>
                </DropdownMenuItem>
              ))}
            </div>
            <DropdownMenuSeparator />
          </>
        )}
        <DropdownMenuItem onSelect={() => { router.push('/app/projects/new'); onNavigate?.() }}>
          <PlusIcon className="h-4 w-4 mr-2" />
          New project
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

// ─── User Footer ─────────────────────────────────────────────────────────────

function UserFooter({ onNavigate }: { onNavigate?: () => void }) {
  const router = useRouter()
  const { user, signOut } = useAuth()
  const { orgs, activeOrg, setActiveOrg } = useOrg()
  const [showCreateOrg, setShowCreateOrg] = useState(false)

  const initials = user?.name
    ? user.name.split(' ').map((n) => n[0]).join('').toUpperCase().slice(0, 2)
    : '?'

  async function handleSignOut() {
    await signOut()
    router.push('/login')
    onNavigate?.()
  }

  return (
    <div className="border-t border-sidebar-border">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button className="w-full flex items-center gap-2.5 px-3 py-3 hover:bg-sidebar-hover transition-colors text-left">
            <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold text-muted-foreground">
              {initials}
            </span>
            <div className="flex-1 min-w-0">
              <p className="text-xs font-medium text-foreground truncate leading-tight">
                {user?.name ?? 'Account'}
              </p>
            </div>
            <MoreHorizontalIcon className="h-4 w-4 text-muted-foreground shrink-0 opacity-60" />
          </button>
        </DropdownMenuTrigger>

        <DropdownMenuContent align="start" side="top" className="w-56 mb-1">
          <DropdownMenuLabel className="text-xs font-normal text-muted-foreground">
            Organizations
          </DropdownMenuLabel>
          {orgs.map((org) => (
            <DropdownMenuItem
              key={org.id}
              onSelect={() => { setActiveOrg(org); onNavigate?.() }}
              className="flex items-center gap-2"
            >
              {activeOrg?.id === org.id
                ? <CheckIcon className="h-3.5 w-3.5 shrink-0" />
                : <span className="h-3.5 w-3.5 shrink-0" />
              }
              <span className="truncate">{org.name}</span>
            </DropdownMenuItem>
          ))}
          <DropdownMenuItem onSelect={() => setShowCreateOrg(true)} className="flex items-center gap-2">
            <PlusIcon className="h-3.5 w-3.5 shrink-0" />
            Create organization
          </DropdownMenuItem>

          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={handleSignOut} className="text-destructive focus:text-destructive">
            <LogOutIcon className="h-4 w-4 mr-2" />
            Sign out
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <CreateOrgDialog open={showCreateOrg} onClose={() => setShowCreateOrg(false)} />
    </div>
  )
}

// ─── Main Content ─────────────────────────────────────────────────────────────

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname()
  const { projects, activeProject } = useProject()
  const { activeOrg, teams } = useOrg()

  // Determine current project from URL path
  const projectIdFromPath = pathname.match(/\/app\/projects\/([^/]+)/)?.[1]
  const currentProject = projects.find((p) => p.id === projectIdFromPath) ?? activeProject

  // Only show projects belonging to the active org
  const orgProjects = activeOrg
    ? projects.filter((p) => p.orgId === activeOrg.id)
    : projects

  // Only show project nav when on a project page within the active org
  const showProjectNav = currentProject && (!activeOrg || currentProject.orgId === activeOrg.id)

  return (
    <div className="flex flex-col h-full">
      {/* Project switcher at top */}
      <div className="border-b border-sidebar-border">
        <ProjectSwitcher projects={orgProjects} currentProject={showProjectNav ? currentProject : null} onNavigate={onNavigate} />
      </div>

      {/* Scrollable middle content */}
      <div className="flex-1 overflow-y-auto py-1">
        {/* Project nav — only when on a project page within the active org */}
        {showProjectNav && currentProject && (
          <div className="space-y-0.5 px-2 py-1">
            <NavItem
              href={`/app/projects/${currentProject.id}/issues`}
              icon={<FileTextIcon className="h-4 w-4" />}
              onNavigate={onNavigate}
            >
              Issues
            </NavItem>
            <NavItem
              href={`/app/projects/${currentProject.id}/workflows`}
              icon={<GitBranchIcon className="h-4 w-4" />}
              onNavigate={onNavigate}
            >
              Workflows
            </NavItem>
            <NavGroup
              href={`/app/projects/${currentProject.id}/settings`}
              icon={<SettingsIcon className="h-4 w-4" />}
              label="Settings"
              onNavigate={onNavigate}
              subLinks={
                <>
                  <NavItem
                    href={`/app/projects/${currentProject.id}/settings/members`}
                    icon={<UsersIcon className="h-4 w-4" />}
                    onNavigate={onNavigate}
                  >
                    Members
                  </NavItem>
                  <NavItem
                    href="/app/settings/api-keys"
                    icon={<KeyIcon className="h-4 w-4" />}
                    onNavigate={onNavigate}
                  >
                    API Keys
                  </NavItem>
                  <NavItem
                    href={`/app/projects/${currentProject.id}/settings/notifications`}
                    icon={<BellIcon className="h-4 w-4" />}
                    onNavigate={onNavigate}
                  >
                    Notifications
                  </NavItem>
                  <NavItem
                    href={`/app/projects/${currentProject.id}/settings/github`}
                    icon={<GitForkIcon className="h-4 w-4" />}
                    onNavigate={onNavigate}
                  >
                    GitHub
                  </NavItem>
                  <NavItem
                    href={`/app/projects/${currentProject.id}/settings/visibility`}
                    icon={<EyeIcon className="h-4 w-4" />}
                    onNavigate={onNavigate}
                  >
                    Visibility
                  </NavItem>
                </>
              }
            />
          </div>
        )}

        {/* Org section — labeled with the actual org name */}
        {activeOrg && (
          <>
            <SidebarSectionLabel>{activeOrg.name}</SidebarSectionLabel>
            <div className="space-y-0.5 px-2">
              <NavItem
                href="/app/org/members"
                icon={<UsersIcon className="h-4 w-4" />}
                onNavigate={onNavigate}
              >
                Members
              </NavItem>
              {teams.length > 0 && (
                <NavItem
                  href="/app/org/teams"
                  icon={<UsersRoundIcon className="h-4 w-4" />}
                  onNavigate={onNavigate}
                >
                  Teams
                </NavItem>
              )}
              <NavItem
                href="/app/org/settings"
                icon={<SettingsIcon className="h-4 w-4" />}
                onNavigate={onNavigate}
              >
                Settings
              </NavItem>
            </div>
          </>
        )}
      </div>

      {/* User footer pinned at bottom */}
      <UserFooter onNavigate={onNavigate} />
    </div>
  )
}

// ─── Sidebar Shell ────────────────────────────────────────────────────────────

export function Sidebar() {
  const { isOpen, closeSidebar, sidebarWidth, setSidebarWidth } = useSidebar()

  function startResize(e: React.MouseEvent) {
    e.preventDefault()
    const startX = e.clientX
    const startWidth = sidebarWidth
    let lastWidth = startWidth

    document.body.classList.add('select-none')

    function onMouseMove(e: MouseEvent) {
      lastWidth = Math.min(400, Math.max(160, startWidth + e.clientX - startX))
      setSidebarWidth(lastWidth)
    }

    function onMouseUp() {
      window.removeEventListener('mousemove', onMouseMove)
      window.removeEventListener('mouseup', onMouseUp)
      document.body.classList.remove('select-none')
      localStorage.setItem('sidebar_width', String(lastWidth))
    }

    window.addEventListener('mousemove', onMouseMove)
    window.addEventListener('mouseup', onMouseUp)
  }

  return (
    <>
      {/* Desktop sidebar */}
      <aside
        style={{ width: sidebarWidth }}
        className="hidden md:flex flex-col bg-sidebar-bg border-r border-sidebar-border relative shrink-0"
      >
        <SidebarContent />
        <div
          className="absolute right-0 top-0 bottom-0 w-1 cursor-col-resize hover:bg-primary/40 transition-colors"
          onMouseDown={startResize}
        />
      </aside>

      {/* Mobile overlay */}
      {isOpen && (
        <div className="fixed inset-0 z-40 md:hidden">
          <div className="fixed inset-0 bg-black/50" onClick={closeSidebar} aria-hidden="true" />
          <aside className="fixed left-0 top-0 bottom-0 w-72 z-40 bg-sidebar-bg border-r border-sidebar-border flex flex-col">
            <SidebarContent onNavigate={closeSidebar} />
          </aside>
        </div>
      )}
    </>
  )
}
