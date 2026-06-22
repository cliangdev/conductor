'use client'

import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import {
  BellIcon,
  BookOpenIcon,
  CheckIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  ChevronsUpDownIcon,
  FileTextIcon,
  FolderIcon,
  GitBranchIcon,
  KeyIcon,
  LogOutIcon,
  MoreHorizontalIcon,
  PlusIcon,
  PuzzleIcon,
  SettingsIcon,
  SlidersHorizontalIcon,
  UsersIcon,
} from 'lucide-react'
import { useEffect, useState } from 'react'
import { CreateWorkspaceDialog } from '@/components/layout/CreateWorkspaceDialog'
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
import { useAuth } from '@/contexts/AuthContext'
import { useEditorChrome } from '@/contexts/EditorChromeContext'
import { cn } from '@/lib/utils'
import type { Project } from '@/types'

// ─── Primitives ───────────────────────────────────────────────────────────────

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

// ─── Workspace Switcher ───────────────────────────────────────────────────────

function WorkspaceSwitcher({
  workspaces,
  currentWorkspace,
  onNavigate,
}: {
  workspaces: Project[]
  currentWorkspace: Project | null
  onNavigate?: () => void
}) {
  const router = useRouter()
  const { setActiveProject } = useProject()
  const [showCreate, setShowCreate] = useState(false)

  function selectWorkspace(workspace: Project) {
    setActiveProject(workspace)
    router.push(`/app/projects/${workspace.id}/issues`)
    onNavigate?.()
  }

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button className="w-full flex items-center gap-2.5 px-3 py-2.5 hover:bg-sidebar-hover transition-colors text-left group">
            <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded bg-primary/15 text-primary">
              <FolderIcon className="h-3.5 w-3.5" />
            </span>
            <span className="flex-1 truncate text-sm font-semibold text-foreground">
              {currentWorkspace ? currentWorkspace.name : 'Workspace'}
            </span>
            <ChevronsUpDownIcon className="h-3.5 w-3.5 text-muted-foreground shrink-0 opacity-50 group-hover:opacity-100 transition-opacity" />
          </button>
        </DropdownMenuTrigger>

        <DropdownMenuContent align="start" className="w-56">
          {workspaces.length > 1 && (
            <>
              <DropdownMenuLabel className="text-xs text-muted-foreground font-normal">
                Switch workspace
              </DropdownMenuLabel>
              <div className="max-h-52 overflow-y-auto">
                {workspaces.map((workspace) => (
                  <DropdownMenuItem
                    key={workspace.id}
                    onSelect={() => selectWorkspace(workspace)}
                    className="flex items-center gap-2"
                  >
                    {currentWorkspace?.id === workspace.id
                      ? <CheckIcon className="h-3.5 w-3.5 shrink-0" />
                      : <span className="h-3.5 w-3.5 shrink-0" />
                    }
                    <span className="truncate">{workspace.name}</span>
                  </DropdownMenuItem>
                ))}
              </div>
              <DropdownMenuSeparator />
            </>
          )}

          <DropdownMenuItem onSelect={() => setShowCreate(true)}>
            <PlusIcon className="h-4 w-4 mr-2" />
            Create workspace
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <CreateWorkspaceDialog open={showCreate} onClose={() => setShowCreate(false)} />
    </>
  )
}

// ─── User / Account footer ────────────────────────────────────────────────────

function UserFooter({ onNavigate }: { onNavigate?: () => void }) {
  const router = useRouter()
  const { user, signOut } = useAuth()

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
            {user?.avatarUrl
              ? <img src={user.avatarUrl} alt="" className="h-7 w-7 shrink-0 rounded-full object-cover" />
              : <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold text-muted-foreground">{initials}</span>
            }
            <div className="flex-1 min-w-0">
              <p className="text-xs font-medium text-foreground truncate leading-tight">
                {user?.name ?? 'Account'}
              </p>
            </div>
            <MoreHorizontalIcon className="h-4 w-4 text-muted-foreground shrink-0 opacity-60" />
          </button>
        </DropdownMenuTrigger>

        <DropdownMenuContent align="start" side="top" className="w-56 mb-1">
          <DropdownMenuLabel className="font-normal">
            <p className="text-xs font-medium text-foreground truncate">{user?.name ?? 'Account'}</p>
            {user?.email && <p className="text-xs text-muted-foreground truncate">{user.email}</p>}
          </DropdownMenuLabel>
          <DropdownMenuSeparator />
          <DropdownMenuItem onSelect={handleSignOut} className="text-destructive focus:text-destructive">
            <LogOutIcon className="h-4 w-4 mr-2" />
            Sign out
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  )
}

// ─── Main Content ─────────────────────────────────────────────────────────────

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname()
  const { projects, activeProject } = useProject()

  // Determine current workspace from URL path
  const projectIdFromPath = pathname.match(/\/app\/projects\/([^/]+)/)?.[1]
  const currentWorkspace = projects.find((p) => p.id === projectIdFromPath) ?? activeProject

  return (
    <div className="flex flex-col h-full">
      {/* Workspace switcher at top */}
      <div className="border-b border-sidebar-border">
        <WorkspaceSwitcher
          workspaces={projects}
          currentWorkspace={currentWorkspace}
          onNavigate={onNavigate}
        />
      </div>

      {/* Scrollable nav */}
      <div className="flex-1 overflow-y-auto py-1">
        {currentWorkspace && (
          <div className="space-y-0.5 px-2 py-1">
            <NavItem
              href={`/app/projects/${currentWorkspace.id}/issues`}
              icon={<FileTextIcon className="h-4 w-4" />}
              onNavigate={onNavigate}
            >
              Issues
            </NavItem>
            <NavItem
              href={`/app/projects/${currentWorkspace.id}/docs`}
              icon={<BookOpenIcon className="h-4 w-4" />}
              onNavigate={onNavigate}
            >
              Docs
            </NavItem>
            <NavItem
              href={`/app/projects/${currentWorkspace.id}/workflows`}
              icon={<GitBranchIcon className="h-4 w-4" />}
              onNavigate={onNavigate}
            >
              Workflows
            </NavItem>
            <NavItem
              href={`/app/projects/${currentWorkspace.id}/integrations`}
              icon={<PuzzleIcon className="h-4 w-4" />}
              onNavigate={onNavigate}
            >
              Integrations
            </NavItem>
            <NavGroup
              href={`/app/projects/${currentWorkspace.id}/settings`}
              icon={<SettingsIcon className="h-4 w-4" />}
              label="Settings"
              onNavigate={onNavigate}
              subLinks={
                <>
                  <NavItem
                    href={`/app/projects/${currentWorkspace.id}/settings/general`}
                    icon={<SlidersHorizontalIcon className="h-4 w-4" />}
                    onNavigate={onNavigate}
                  >
                    General
                  </NavItem>
                  <NavItem
                    href={`/app/projects/${currentWorkspace.id}/settings/members`}
                    icon={<UsersIcon className="h-4 w-4" />}
                    onNavigate={onNavigate}
                  >
                    Members
                  </NavItem>
                  <NavItem
                    href={`/app/projects/${currentWorkspace.id}/settings/api-keys`}
                    icon={<KeyIcon className="h-4 w-4" />}
                    onNavigate={onNavigate}
                  >
                    API Keys
                  </NavItem>
                  <NavItem
                    href={`/app/projects/${currentWorkspace.id}/settings/notifications`}
                    icon={<BellIcon className="h-4 w-4" />}
                    onNavigate={onNavigate}
                  >
                    Notifications
                  </NavItem>
                  <NavItem
                    href={`/app/projects/${currentWorkspace.id}/settings/integrations`}
                    icon={<PuzzleIcon className="h-4 w-4" />}
                    onNavigate={onNavigate}
                  >
                    Integrations
                  </NavItem>
                </>
              }
            />
          </div>
        )}
      </div>

      {/* User / account menu pinned at the bottom */}
      <UserFooter onNavigate={onNavigate} />
    </div>
  )
}

// ─── Sidebar Shell ────────────────────────────────────────────────────────────

export function Sidebar() {
  const { isOpen, closeSidebar, sidebarWidth, setSidebarWidth, sidebarCollapsed, setSidebarCollapsed } = useSidebar()
  const { fullscreen } = useEditorChrome()

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

  if (fullscreen) return null

  return (
    <>
      {/* Desktop sidebar */}
      {sidebarCollapsed ? (
        <aside className="hidden md:flex w-4 shrink-0 border-r border-sidebar-border bg-sidebar-bg items-center justify-center">
          <button
            type="button"
            onClick={() => setSidebarCollapsed(false)}
            title="Expand navigation"
            className="h-full w-full flex items-center justify-center text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
          >
            <ChevronRightIcon className="h-3.5 w-3.5" />
          </button>
        </aside>
      ) : (
        <aside className="hidden md:flex shrink-0 border-r border-sidebar-border bg-sidebar-bg">
          {/* Content area with resize handle */}
          <div style={{ width: sidebarWidth }} className="relative flex flex-col overflow-hidden">
            <SidebarContent />
            <div
              className="absolute right-0 top-0 bottom-0 w-1 cursor-col-resize hover:bg-primary/40 transition-colors"
              onMouseDown={startResize}
            />
          </div>
          {/* Collapse strip */}
          <button
            type="button"
            onClick={() => setSidebarCollapsed(true)}
            title="Collapse navigation"
            className="w-4 shrink-0 flex items-center justify-center text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
          >
            <ChevronLeftIcon className="h-3.5 w-3.5" />
          </button>
        </aside>
      )}

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
