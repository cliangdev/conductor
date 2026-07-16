'use client'

export const dynamic = 'force-dynamic'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { FolderPlusIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { useProject } from '@/contexts/ProjectContext'

export default function ProjectsPage() {
  const router = useRouter()
  const { projects, activeProject, loading } = useProject()

  useEffect(() => {
    if (loading) return
    if (projects.length === 0) return

    const target = activeProject ?? projects[0]
    router.replace(`/app/projects/${target.id}/engineering/issues`)
  }, [loading, projects, activeProject])

  if (loading) {
    return (
      <div className="flex flex-col items-center justify-center min-h-[calc(100vh-3.5rem)] gap-3 px-4">
        <Skeleton className="h-16 w-16 rounded-lg" />
        <Skeleton className="h-6 w-64" />
        <Skeleton className="h-4 w-80" />
      </div>
    )
  }

  if (projects.length > 0) return null

  return (
    <div className="flex items-center justify-center min-h-[calc(100vh-3.5rem)] px-4">
      <EmptyState
        icon={FolderPlusIcon}
        title="Create your first workspace"
        description="A workspace keeps your PRDs, issues, and reviews together for the whole team."
        action={
          <Button size="lg" onClick={() => router.push('/app/projects/new')}>
            Create workspace
          </Button>
        }
      />
    </div>
  )
}
