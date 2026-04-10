'use client'

export const dynamic = 'force-dynamic'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { FolderPlusIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useProject } from '@/contexts/ProjectContext'

export default function ProjectsPage() {
  const router = useRouter()
  const { projects, activeProject, loading } = useProject()

  useEffect(() => {
    if (loading) return
    if (projects.length === 0) return

    const target = activeProject ?? projects[0]
    router.replace(`/app/projects/${target.id}/issues`)
  }, [loading, projects, activeProject])

  if (loading) return null

  if (projects.length > 0) return null

  return (
    <div className="flex flex-col items-center justify-center min-h-[calc(100vh-3.5rem)] text-center px-4">
      <div className="flex items-center justify-center w-16 h-16 rounded-2xl bg-muted mb-6">
        <FolderPlusIcon className="w-8 h-8 text-muted-foreground" />
      </div>
      <h1 className="text-2xl font-semibold text-foreground mb-2">Create your first project</h1>
      <p className="text-muted-foreground max-w-sm mb-8">
        Projects organize your PRDs, issues, and team reviews in one place.
      </p>
      <Button size="lg" onClick={() => router.push('/app/projects/new')}>
        Create project
      </Button>
    </div>
  )
}
