'use client'

import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import { BellIcon, ChevronDownIcon, ChevronRightIcon, FileTextIcon, PlusIcon, SettingsIcon, TerminalIcon, UsersIcon } from 'lucide-react'
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
import { cn } from '@/lib/utils'
import type { Project } from '@/types'

function SidebarNavLink({
  href,
  icon,
  children,
  onNavigate,
}: {
  href: string
  icon: React.ReactNode
  children: React.ReactNode
  onNavigate?: () => void
}) {
  const pathname = usePathname()
  const isActive = pathname.startsWith(href)
  return (
    <Link
      href={href}
      onClick={onNavigate}
      className={cn(
        'flex items-center gap-2.5 px-2.5 py-2 rounded-md text-sm transition-colors',
        isActive
          ? 'bg-sidebar-active text-sidebar-active-text font-medium'
          : 'text-foreground hover:bg-sidebar-hover'
      )}
    >
      <span className="h-4 w-4 shrink-0">{icon}</span>
      <span className="truncate">{children}</span>
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

function SidebarContent({ onNavigate }: { onNavigate?: () => void }) {
  const router = useRouter()
  const { projects, activeProject, setActiveProject } = useProject()

  function handleProjectSelect(project: Project) {
    setActiveProject(project)
    router.push(`/app/projects/${project.id}/issues`)
    onNavigate?.()
  }

  return (
    <div className="flex flex-col flex-1 min-h-0">
      {/* Project selector header */}
      <div className="p-3 border-b border-sidebar-border">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button className="w-full flex items-center gap-2 px-2.5 py-2 rounded-md text-sm hover:bg-sidebar-hover transition-colors text-left">
              <span className="flex-1 truncate font-medium text-foreground">
                {activeProject ? activeProject.name : 'Select project'}
              </span>
              <ChevronDownIcon className="h-4 w-4 text-muted-foreground shrink-0" />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-56">
            <div className="max-h-64 overflow-y-auto">
              {projects.map((project) => (
                <DropdownMenuItem
                  key={project.id}
                  onSelect={() => handleProjectSelect(project)}
                  className={activeProject?.id === project.id ? 'font-semibold' : ''}
                >
                  {project.name}
                </DropdownMenuItem>
              ))}
            </div>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={() => { router.push('/app/projects/new'); onNavigate?.() }}>
              <PlusIcon className="h-4 w-4 mr-2" />
              New project
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>

      {/* Nav links */}
      {activeProject && (
        <nav className="flex-1 p-2 space-y-0.5">
          <SidebarNavLink
            href={`/app/projects/${activeProject.id}/issues`}
            icon={<FileTextIcon className="h-4 w-4" />}
            onNavigate={onNavigate}
          >
            Issues
          </SidebarNavLink>
          <SidebarNavGroup
            href={`/app/projects/${activeProject.id}/settings`}
            icon={<SettingsIcon className="h-4 w-4" />}
            label="Settings"
            onNavigate={onNavigate}
            subLinks={
              <>
                <SidebarNavLink
                  href={`/app/projects/${activeProject.id}/settings/members`}
                  icon={<UsersIcon className="h-4 w-4" />}
                  onNavigate={onNavigate}
                >
                  Members
                </SidebarNavLink>
                <SidebarNavLink
                  href={`/app/projects/${activeProject.id}/settings/tools`}
                  icon={<TerminalIcon className="h-4 w-4" />}
                  onNavigate={onNavigate}
                >
                  Tools
                </SidebarNavLink>
                <SidebarNavLink
                  href={`/app/projects/${activeProject.id}/settings/notifications`}
                  icon={<BellIcon className="h-4 w-4" />}
                  onNavigate={onNavigate}
                >
                  Notifications
                </SidebarNavLink>
              </>
            }
          />
        </nav>
      )}
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
