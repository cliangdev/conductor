'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { ChevronDownIcon, PlusIcon, MenuIcon, XIcon } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { ThemeToggle } from '@/components/ui/ThemeToggle'
import { useAuth } from '@/contexts/AuthContext'
import { useProject } from '@/contexts/ProjectContext'

export function Navbar() {
  const router = useRouter()
  const { user, signOut } = useAuth()
  const { projects, activeProject, setActiveProject, loading } = useProject()
  const [drawerOpen, setDrawerOpen] = useState(false)

  const userInitials = user?.name
    ? user.name
        .split(' ')
        .map((n) => n[0])
        .join('')
        .toUpperCase()
        .slice(0, 2)
    : '?'

  async function handleSignOut() {
    setDrawerOpen(false)
    await signOut()
    router.push('/login')
  }

  function handleProjectSelect(project: { id: string; name: string }) {
    setActiveProject(project as Parameters<typeof setActiveProject>[0])
    router.push(`/app/projects/${project.id}/issues`)
    setDrawerOpen(false)
  }

  return (
    <>
      <nav className="flex items-center justify-between h-14 px-4 border-b border-border bg-background sticky top-0 z-30">
        {/* Left: hamburger (mobile) + logo */}
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="icon"
            className="md:hidden"
            onClick={() => setDrawerOpen(true)}
            aria-label="Open menu"
          >
            <MenuIcon className="h-5 w-5" />
          </Button>
          <span className="font-bold text-lg tracking-tight text-foreground">Conductor</span>
        </div>

        {/* Center: project selector (desktop only) */}
        <div className="hidden md:flex items-center">
          {!loading && projects.length === 0 ? (
            <Button
              variant="outline"
              className="flex items-center gap-2 text-sm"
              onClick={() => router.push('/app/projects/new')}
            >
              <PlusIcon className="h-4 w-4" />
              New project
            </Button>
          ) : projects.length > 0 ? (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" className="flex items-center gap-2" data-testid="project-selector">
                  <span className="text-sm max-w-48 truncate">
                    {activeProject ? activeProject.name : 'Select project'}
                  </span>
                  <ChevronDownIcon className="h-4 w-4 text-muted-foreground shrink-0" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="center" className="w-56">
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
                <DropdownMenuItem onSelect={() => router.push('/app/projects/new')}>
                  <PlusIcon className="h-4 w-4 mr-2" />
                  New project
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          ) : null}
        </div>

        {/* Right: theme toggle + user menu */}
        <div className="flex items-center gap-1">
          <ThemeToggle />
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" className="p-0 h-8 w-8 rounded-full">
                <Avatar className="h-8 w-8">
                  <AvatarImage src={user?.avatarUrl ?? undefined} alt={user?.name ?? 'User'} />
                  <AvatarFallback>{userInitials}</AvatarFallback>
                </Avatar>
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-48">
              {user && (
                <>
                  <div className="px-2 py-1.5">
                    <p className="text-sm font-medium text-foreground">{user.name}</p>
                    <p className="text-xs text-muted-foreground">{user.email}</p>
                  </div>
                  <DropdownMenuSeparator />
                </>
              )}
              <DropdownMenuItem onSelect={handleSignOut} className="text-destructive">
                Sign out
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </nav>

      {/* Mobile drawer */}
      {drawerOpen && (
        <div className="fixed inset-0 z-40 md:hidden">
          {/* Backdrop */}
          <div
            className="fixed inset-0 bg-black/50"
            onClick={() => setDrawerOpen(false)}
            aria-hidden="true"
          />
          {/* Drawer panel */}
          <div className="fixed left-0 top-0 bottom-0 w-72 bg-sidebar-bg border-r border-sidebar-border flex flex-col">
            {/* Drawer header */}
            <div className="flex items-center justify-between h-14 px-4 border-b border-sidebar-border">
              <span className="font-bold text-lg tracking-tight text-foreground">Conductor</span>
              <Button
                variant="ghost"
                size="icon"
                onClick={() => setDrawerOpen(false)}
                aria-label="Close menu"
              >
                <XIcon className="h-5 w-5" />
              </Button>
            </div>

            {/* Project list */}
            <div className="flex-1 overflow-y-auto p-3">
              <p className="text-xs font-medium text-foreground-subtle uppercase tracking-wider px-2 mb-2">
                Projects
              </p>
              {projects.map((project) => (
                <button
                  key={project.id}
                  onClick={() => handleProjectSelect(project)}
                  className={`w-full text-left px-2 py-2 rounded-md text-sm transition-colors ${
                    activeProject?.id === project.id
                      ? 'bg-sidebar-active text-sidebar-active-text font-medium'
                      : 'text-foreground hover:bg-sidebar-hover'
                  }`}
                >
                  {project.name}
                </button>
              ))}
              <button
                onClick={() => { router.push('/app/projects/new'); setDrawerOpen(false) }}
                className="w-full text-left px-2 py-2 rounded-md text-sm text-muted-foreground hover:bg-sidebar-hover transition-colors flex items-center gap-2 mt-1"
              >
                <PlusIcon className="h-4 w-4" />
                New project
              </button>
            </div>

            {/* Drawer footer */}
            <div className="p-3 border-t border-sidebar-border">
              {user && (
                <div className="flex items-center gap-3 px-2 py-2 mb-2">
                  <Avatar className="h-7 w-7 shrink-0">
                    <AvatarImage src={user.avatarUrl ?? undefined} alt={user.name ?? 'User'} />
                    <AvatarFallback className="text-xs">{userInitials}</AvatarFallback>
                  </Avatar>
                  <div className="min-w-0">
                    <p className="text-sm font-medium text-foreground truncate">{user.name}</p>
                    <p className="text-xs text-muted-foreground truncate">{user.email}</p>
                  </div>
                </div>
              )}
              <button
                onClick={handleSignOut}
                className="w-full text-left px-2 py-2 rounded-md text-sm text-destructive hover:bg-destructive/10 transition-colors"
              >
                Sign out
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
