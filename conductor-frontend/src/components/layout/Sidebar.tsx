'use client'

import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import {
  CheckIcon,
  ChevronRightIcon,
  ChevronsUpDownIcon,
  FileTextIcon,
  FolderIcon,
  LogOutIcon,
  PanelLeftCloseIcon,
  PanelLeftIcon,
  PlusIcon,
  SearchIcon,
  SettingsIcon,
} from 'lucide-react'
import { useTheme } from 'next-themes'
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
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { ShortcutKbd, useModKeyLabel } from '@/components/ui/shortcut-kbd'
import { useSidebar } from '@/contexts/SidebarContext'
import { useProject } from '@/contexts/ProjectContext'
import { useAuth } from '@/contexts/AuthContext'
import { useEditorChrome } from '@/contexts/EditorChromeContext'
import { humanizeId, useSidebarWorkNav, workItemListPath, groupByArea } from '@/lib/workflows'
import { AUTOMATION_NAV, SETTINGS_NAV, WORKSPACE_NAV, useCurrentWorkspace, workspaceHomePath } from '@/lib/navigation'
import { THEME_OPTIONS } from '@/lib/theme'
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
    router.push(workspaceHomePath(workspace.id))
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

// ─── Search / Command palette launcher ────────────────────────────────────────

function SidebarSearchButton() {
  const { setPaletteOpen } = useSidebar()
  const modKey = useModKeyLabel()
  return (
    <button
      type="button"
      onClick={() => setPaletteOpen(true)}
      className="flex items-center gap-2 w-full rounded-md border border-sidebar-border bg-background px-2.5 py-1.5 text-left text-sm text-muted-foreground hover:border-border-strong hover:text-foreground transition-colors"
    >
      <SearchIcon className="h-3.5 w-3.5 shrink-0" />
      <span className="flex-1">Search…</span>
      <ShortcutKbd className="hidden sm:inline-flex">{modKey}</ShortcutKbd>
    </button>
  )
}

// ─── User / Account menu ───────────────────────────────────────────────────────
//
// Shared by the expanded footer and the collapsed rail (item 10) so sign-out and theme switching
// stay reachable regardless of sidebar width — only the trigger's appearance differs.

function UserAccountMenu({ collapsed = false, onNavigate }: { collapsed?: boolean; onNavigate?: () => void }) {
  const router = useRouter()
  const { user, signOut } = useAuth()
  const { theme, setTheme } = useTheme()

  const initials = user?.name
    ? user.name.split(' ').map((n) => n[0]).join('').toUpperCase().slice(0, 2)
    : '?'

  async function handleSignOut() {
    await signOut()
    router.push('/login')
    onNavigate?.()
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        {collapsed ? (
          <button
            type="button"
            aria-label="Account menu"
            className="rounded-md p-1.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
          >
            <Avatar className="h-6 w-6">
              <AvatarImage src={user?.avatarUrl ?? undefined} alt={user?.name ?? 'User'} />
              <AvatarFallback className="text-[10px] font-semibold">{initials}</AvatarFallback>
            </Avatar>
          </button>
        ) : (
          <button className="w-full flex items-center gap-2.5 px-3 py-3 hover:bg-sidebar-hover transition-colors text-left">
            <Avatar className="h-7 w-7 shrink-0">
              <AvatarImage src={user?.avatarUrl ?? undefined} alt={user?.name ?? 'User'} />
              <AvatarFallback className="text-xs font-semibold">{initials}</AvatarFallback>
            </Avatar>
            <div className="flex-1 min-w-0">
              <p className="text-xs font-medium text-foreground truncate leading-tight">
                {user?.name ?? 'Account'}
              </p>
            </div>
            <ChevronsUpDownIcon className="h-3.5 w-3.5 text-muted-foreground shrink-0 opacity-60" />
          </button>
        )}
      </DropdownMenuTrigger>

      <DropdownMenuContent align="start" side={collapsed ? 'right' : 'top'} className="w-56 mb-1">
        <DropdownMenuLabel className="font-normal">
          <p className="text-xs font-medium text-foreground truncate">{user?.name ?? 'Account'}</p>
          {user?.email && <p className="text-xs text-muted-foreground truncate">{user.email}</p>}
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        {THEME_OPTIONS.map(({ value, label, icon: Icon }) => (
          <DropdownMenuItem key={value} onSelect={() => setTheme(value)} className="flex items-center gap-2">
            <Icon className="h-4 w-4" />
            <span className="flex-1">{label}</span>
            {theme === value && <CheckIcon className="h-3.5 w-3.5" />}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={handleSignOut} className="text-destructive focus:text-destructive">
          <LogOutIcon className="h-4 w-4 mr-2" />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function UserFooter({ onNavigate }: { onNavigate?: () => void }) {
  return (
    <div className="border-t border-sidebar-border">
      <UserAccountMenu onNavigate={onNavigate} />
    </div>
  )
}

// ─── Main Content ─────────────────────────────────────────────────────────────

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="px-2 pb-1 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
      {children}
    </div>
  )
}

function NavSkeleton() {
  return (
    <div className="space-y-1 mb-4" aria-hidden="true">
      {[75, 60, 85].map((w) => (
        <div key={w} className="h-8 rounded-md bg-muted animate-pulse" style={{ width: `${w}%` }} />
      ))}
    </div>
  )
}

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname()
  const { projects } = useProject()
  const { accessToken } = useAuth()
  const currentWorkspace = useCurrentWorkspace()

  // Dynamic Work nav: one entry per sidebar-enabled, published lifecycle Workflow, grouped by area.
  // Shared with the CommandPalette via the useSidebarWorkNav hook. Scoped strictly to the URL's
  // project segment (not the activeProject fallback above) so it doesn't fetch a workflow list
  // while on project-less routes like the workspace picker.
  const projectIdFromPath = pathname.match(/\/app\/projects\/([^/]+)/)?.[1]
  const { entries: workNav, loading: navLoading } = useSidebarWorkNav(projectIdFromPath, accessToken)

  return (
    <div className="flex flex-col h-full">
      {/* Workspace switcher at top */}
      <div className="border-b border-sidebar-border">
        <WorkspaceSwitcher
          workspaces={projects}
          currentWorkspace={currentWorkspace}
          onNavigate={onNavigate}
        />
        <div className="px-2 pb-2">
          <SidebarSearchButton />
        </div>
      </div>

      {/* Scrollable nav */}
      <div className="flex-1 overflow-y-auto py-1">
        {currentWorkspace && (
          <div className="px-2 py-1">
            {workNav.length > 0 ? (
              groupByArea(workNav).map(([area, entries]) => (
                <div key={area}>
                  <SectionLabel>{humanizeId(area)}</SectionLabel>
                  <div className="space-y-0.5 mb-4">
                    {entries.map((entry) => (
                      <NavItem
                        key={entry.slug}
                        href={workItemListPath(currentWorkspace.id, entry.area, entry.noun)}
                        icon={<FileTextIcon className="h-4 w-4" />}
                        onNavigate={onNavigate}
                      >
                        {entry.label}
                      </NavItem>
                    ))}
                  </div>
                </div>
              ))
            ) : navLoading ? (
              <>
                <SectionLabel>Work</SectionLabel>
                <NavSkeleton />
              </>
            ) : null}

            <SectionLabel>Workspace</SectionLabel>
            <div className="space-y-0.5 mb-4">
              {WORKSPACE_NAV.map(({ key, label, icon: Icon, path }) => (
                <NavItem key={key} href={path(currentWorkspace.id)} icon={<Icon className="h-4 w-4" />} onNavigate={onNavigate}>
                  {label}
                </NavItem>
              ))}
            </div>

            <SectionLabel>Automation</SectionLabel>
            <div className="space-y-0.5 mb-4">
              {AUTOMATION_NAV.map(({ key, label, icon: Icon, path }) => (
                <NavItem key={key} href={path(currentWorkspace.id)} icon={<Icon className="h-4 w-4" />} onNavigate={onNavigate}>
                  {label}
                </NavItem>
              ))}
            </div>

            <div className="space-y-0.5">
              <NavGroup
                href={`/app/projects/${currentWorkspace.id}/settings`}
                icon={<SettingsIcon className="h-4 w-4" />}
                label="Settings"
                onNavigate={onNavigate}
                subLinks={
                  <>
                    {SETTINGS_NAV.map(({ key, label, icon: Icon, path }) => (
                      <NavItem key={key} href={path(currentWorkspace.id)} icon={<Icon className="h-4 w-4" />} onNavigate={onNavigate}>
                        {label}
                      </NavItem>
                    ))}
                  </>
                }
              />
            </div>
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
  const {
    isOpen,
    closeSidebar,
    sidebarWidth,
    setSidebarWidth,
    sidebarCollapsed,
    setSidebarCollapsed,
    setPaletteOpen,
  } = useSidebar()
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
        <aside className="hidden md:flex w-10 shrink-0 border-r border-sidebar-border bg-sidebar-bg flex-col items-center py-2 gap-1">
          <button
            type="button"
            onClick={() => setSidebarCollapsed(false)}
            title="Expand navigation"
            aria-label="Expand navigation"
            className="rounded-md p-1.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
          >
            <PanelLeftIcon className="h-4 w-4" />
          </button>
          <button
            type="button"
            onClick={() => setPaletteOpen(true)}
            title="Search"
            aria-label="Search"
            className="rounded-md p-1.5 text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
          >
            <SearchIcon className="h-4 w-4" />
          </button>
          <div className="flex-1" />
          <UserAccountMenu collapsed />
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
          {/* Collapse rail */}
          <button
            type="button"
            onClick={() => setSidebarCollapsed(true)}
            title="Collapse navigation"
            aria-label="Collapse navigation"
            className="w-6 shrink-0 flex items-start justify-center pt-2 text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
          >
            <PanelLeftCloseIcon className="h-4 w-4" />
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
