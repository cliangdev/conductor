'use client'

import { useEffect } from 'react'
import { useParams } from 'next/navigation'
import { useProject } from '@/contexts/ProjectContext'

export default function ProjectLayout({ children }: { children: React.ReactNode }) {
  const { projectId } = useParams<{ projectId: string }>()
  const { projects, activeProject, setActiveProject } = useProject()

  useEffect(() => {
    if (!projectId) return
    const project = projects.find((p) => p.id === projectId)
    if (!project) return

    if (activeProject?.id !== project.id) {
      setActiveProject(project)
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, projects, activeProject?.id, setActiveProject])

  return <>{children}</>
}
