'use client'

import { useRouter } from 'next/navigation'
import { ChevronDownIcon, PlusIcon } from 'lucide-react'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/contexts/AuthContext'
import { useProject } from '@/contexts/ProjectContext'

export function Navbar() {
  const router = useRouter()
  const { user, signOut } = useAuth()
  const { projects, activeProject, setActiveProject, loading } = useProject()

  const userInitials = user?.name
    ? user.name
        .split(' ')
        .map((n) => n[0])
        .join('')
        .toUpperCase()
        .slice(0, 2)
    : '?'

  async function handleSignOut() {
    await signOut()
    router.push('/login')
  }

  return (
    <nav className="flex items-center justify-between h-14 px-4 border-b bg-white">
      <span className="font-bold text-lg tracking-tight text-gray-900">Conductor</span>

      <div className="flex items-center gap-4">
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
                <span className="text-sm">
                  {activeProject ? activeProject.name : 'Select project'}
                </span>
                <ChevronDownIcon className="h-4 w-4 text-gray-500" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="center" className="w-56">
              <div className="max-h-64 overflow-y-auto">
                {projects.map((project) => (
                  <DropdownMenuItem
                    key={project.id}
                    onSelect={() => {
                      setActiveProject(project)
                      router.push(`/app/projects/${project.id}/issues`)
                    }}
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
                  <p className="text-sm font-medium">{user.name}</p>
                  <p className="text-xs text-gray-500">{user.email}</p>
                </div>
                <DropdownMenuSeparator />
              </>
            )}
            <DropdownMenuItem onSelect={handleSignOut} className="text-red-600">
              Sign out
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </nav>
  )
}
