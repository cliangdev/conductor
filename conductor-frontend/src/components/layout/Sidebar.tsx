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
  GitBranchIcon,
  GitForkIcon,
  KeyIcon,
  LockIcon,
  PlusIcon,
  SettingsIcon,
  UsersIcon,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useSidebar } from '@/contexts/SidebarContext'
import { useProject } from '@/contexts/ProjectContext'
import { useOrg } from '@/contexts/OrgContext'
import { cn } from '@/lib/utils'
import type { Project, Team } from '@/types'

function teamAbbrev(name: string): string {
  const words = name.trim().split(/\s+/)
  if (words.length >= 2) return words.map((w) => w[0]).join('').toUpperCase().slice(0, 3)
  return name.slice(0, 3).toUpperCase()
}

function SidebarSectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="px-2.5 pt-3 pb-1 text-xs font-semibold text-muted-foreground uppercase tracking-wider">
      {children}
    </p>
  )
}

function SidebarNavLink({
  href,
  icon,
  children,
  onNavigate,
  className,
}: {
  href: string
  icon?: React.ReactNode
  children: React.ReactNode
  onNavigate?: () => void
  className?: string
}) {
  const pathname = usePathname()
  const isActive = pathname === href || (href !== '/app/projects/new' && pathname.startsWith(href))
  return (
    <Link
      href={href}
      onClick={onNavigate}
      className={cn(
        'flex items-center gap-2.5 px-2.5 py-2 rounded-md text-sm transition-colors',
        isActive
          ? 'bg-sidebar-active text-sidebar-active-text font-medium'
          : 'text-foreground hover:bg-sidebar-hover',
        className
      )}
    >
      {icon && <span className="h-4 w-4 shrink-0">{icon}</span>}
      <span className="truncate flex-1">{children}</span>
    </Link>
  )
}

function SidebarNavGroup({
  href,
  icon,
  label,
  subLinks,
  onNavigate,
}: {
  href: string
  icon: React.ReactNode
  label: string
  subLinks: React.ReactNode
  onNavigate?: () => void
}) {
  const pathname = usePathname()
  const isGroupActive = pathname.startsWith(href)
  const [expanded, setExpanded] = useState(isGroupActive)

  useEffect(() => {
    if (isGroupActive) setExpanded(true)
  }, [isGroupActive])

  return (
    <div>
      <button
        onClick={() => setExpanded((e) => !e)}
        className={cn(
          'flex items-center gap-2.5 px-2.5 py-2 rounded-md text-sm w-full transition-colors',
          isGroupActive
            ? 'bg-sidebar-active text-sidebar-active-text font-medium'
            : 'text-foreground hover:bg-sidebar-hover'
        )}
      >
        <span className="h-4 w-4 shrink-0">{icon}</span>
        <span className="flex-1 truncate text-left">{label}</span>
        <ChevronRightIcon className={cn('h-3 w-3 transition-transform shrink-0', expanded && 'rotate-90')} />
      </button>
      {expanded && (
        <div className="ml-6 mt-0.5 space-y-0.5">
          {subLinks}
        </div>
      )}
    </div>
  )
}

function ProjectRow({
  project,
  teams,
  onNavigate,
}: {
  project: Project
  teams: Team[]
  onNavigate?: () => void
}) {
  const pathname = usePathname()
  const isActive = pathname.startsWith(`/app/projects/${project.id}`)
  const isInSubNav = isActive && pathname.includes('/app/projects/' + project.id + '/')

  const owningTeam = project.teamId ? teams.find((t) => t.id === project.teamId) : null
  const showLock = project.visibility === 'PRIVATE'
  const showTeamChip = project.visibility === 'TEAM' && owningTeam

  return (
    <div>
      <Link
        href={`/app/projects/${project.id}/issues`}
        onClick={onNavigate}
        className={cn(
          'flex items-center gap-2 px-2.5 py-2 rounded-md text-sm transition-colors w-full',
          isActive
            ? 'bg-sidebar-active text-sidebar-active-text font-medium'
            : 'text-foreground hover:bg-sidebar-hover'
        )}
      >
        <span className="truncate flex-1">{project.name}</span>
        {showLock && <LockIcon className="h-3 w-3 shrink-0 text-muted-foreground" />}
        {showTeamChip && (
          <span className="shrink-0 text-xs bg-muted text-muted-foreground rounded px-1 py-0.5 font-mono">
            {teamAbbrev(owningTeam.name)}
          </span>
        )}
      </Link>

      {isInSubNav && (
        <div className="ml-4 mt-0.5 space-y-0.5">
          <SidebarNavLink
            href={`/app/projects/${project.id}/issues`}
            icon={<FileTextIcon className="h-4 w-4" />}
            onNavigate={onNavigate}
          >
            Issues
          </SidebarNavLink>
          <SidebarNavLink
            href={`/app/projects/${project.id}/workflows`}
            icon={<GitBranchIcon className="h-4 w-4" />}
            onNavigate={onNavigate}
          >
            Workflows
          </SidebarNavLink>
          <SidebarNavGroup
            href={`/app/projects/${project.id}/settings`}
            icon={<SettingsIcon className="h-4 w-4" />}
            label="Settings"
            onNavigate={onNavigate}
            subLinks={
              <>
                <SidebarNavLink
                  href={`/app/projects/${project.id}/settings/members`}
                  icon={<UsersIcon className="h-4 w-4" />}
                  onNavigate={onNavigate}
                >
                  Members
                </SidebarNavLink>
                <SidebarNavLink
                  href="/app/settings/api-keys"
                  icon={<KeyIcon className="h-4 w-4" />}
                  onNavigate={onNavigate}
                >
                  API Keys
                </SidebarNavLink>
                <SidebarNavLink
                  href={`/app/projects/${project.id}/settings/notifications`}
                  icon={<BellIcon className="h-4 w-4" />}
                  onNavigate={onNavigate}
                >
                  Notifications
                </SidebarNavLink>
                <SidebarNavLink
                  href={`/app/projects/${project.id}/settings/github`}
                  icon={<GitForkIcon className="h-4 w-4" />}
                  onNavigate={onNavigate}
                >
                  GitHub
                </SidebarNavLink>
                <SidebarNavLink
                  href={`/app/projects/${project.id}/settings/visibility`}
                  icon={<EyeIcon className="h-4 w-4" />}
                  onNavigate={onNavigate}
                >
                  Visibility
                </SidebarNavLink>
              </>
            }
          />
        </div>
      )}
    </div>
  )
}

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const router = useRouter()
  const { projects } = useProject()
  const { activeOrg, orgs, teams, setActiveOrg } = useOrg()

  return (
    <div className="flex flex-col flex-1 min-h-0 overflow-y-auto">
      {/* Org switcher */}
      <div className="px-3 py-2.5 border-b border-sidebar-border">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button className="w-full flex items-center gap-2 px-2.5 py-2 rounded-md text-sm hover:bg-sidebar-hover transition-colors text-left">
              <span className="flex-1 truncate font-semibold text-foreground">
                {activeOrg ? activeOrg.name : 'Select org'}
              </span>
              <ChevronDownIcon className="h-4 w-4 text-muted-foreground shrink-0" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-56">
            {orgs.map((org) => (
              <DropdownMenuItem
                key={org.id}
                onSelect={() => { setActiveOrg(org); onNavigate?.() }}
                className="flex items-center gap-2"
              >
                {activeOrg?.id === org.id
                  ? <CheckIcon className="h-4 w-4 shrink-0" />
                  : <span className="h-4 w-4 shrink-0" />
                }
                {org.name}
              </DropdownMenuItem>
            ))}
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => { router.push('/onboarding'); onNavigate?.() }}>
              <PlusIcon className="h-4 w-4 mr-2" />
              New organization
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      <div className="flex-1 p-2">
        {/* Projects section */}
        <SidebarSectionLabel>Projects</SidebarSectionLabel>
        <div className="space-y-0.5 mb-1">
          <Link
            href="/app/projects/new"
            onClick={onNavigate}
            className="flex items-center gap-2 px-2.5 py-1.5 rounded-md text-sm text-muted-foreground hover:bg-sidebar-hover hover:text-foreground transition-colors"
          >
            <PlusIcon className="h-3.5 w-3.5 shrink-0" />
            New project
          </Link>
          {projects.map((project) => (
            <ProjectRow key={project.id} project={project} teams={teams} onNavigate={onNavigate} />
          ))}
        </div>

        {/* Teams section — only when org has teams */}
        {teams.length > 0 && (
          <>
            <SidebarSectionLabel>Teams</SidebarSectionLabel>
            <div className="space-y-0.5 mb-1">
              {teams.map((team) => (
                <SidebarNavLink
                  key={team.id}
                  href={`/app/org/teams/${team.id}`}
                  onNavigate={onNavigate}
                >
                  {team.name}
                </SidebarNavLink>
              ))}
            </div>
          </>
        )}

        {/* Workspace section */}
        {activeOrg && (
          <>
            <SidebarSectionLabel>Workspace</SidebarSectionLabel>
            <div className="space-y-0.5">
              <SidebarNavLink
                href="/app/org/members"
                icon={<UsersIcon className="h-4 w-4" />}
                onNavigate={onNavigate}
              >
                Members
              </SidebarNavLink>
              <SidebarNavLink
                href="/app/org/settings"
                icon={<SettingsIcon className="h-4 w-4" />}
                onNavigate={onNavigate}
              >
                Settings
              </SidebarNavLink>
            </div>
          </>
        )}
      </div>
    </div>
  )
}

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
        {/* Resize handle */}
        <div
          className="absolute right-0 top-0 bottom-0 w-1 cursor-col-resize hover:bg-primary/40 transition-colors"
          onMouseDown={startResize}
        />
      </aside>

      {/* Mobile overlay */}
      {isOpen && (
        <div className="fixed inset-0 z-40 md:hidden">
          <div
            className="fixed inset-0 bg-black/50"
            onClick={closeSidebar}
            aria-hidden="true"
          />
          <aside className="fixed left-0 top-0 bottom-0 w-72 z-40 bg-sidebar-bg border-r border-sidebar-border flex flex-col">
            <SidebarContent onNavigate={closeSidebar} />
          </aside>
        </div>
      )}
    </>
  )
}
