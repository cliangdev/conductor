'use client'

import { useEffect } from 'react'
import { useParams } from 'next/navigation'
import { useProject } from '@/contexts/ProjectContext'
import { useAuth } from '@/contexts/AuthContext'
import { PermissionsProvider } from '@/contexts/PermissionsContext'
import { fetchSidebarWorkflows } from '@/lib/workflows'

export default function ProjectLayout({ children }: { children: React.ReactNode }) {
  const { projectId } = useParams<{ projectId: string }>()
  const { projects, activeProject, setActiveProject } = useProject()
  const { accessToken } = useAuth()

  // Pre-warm the sidebar workflow cache as early as this layout mounts — before the sidebar's own
  // effect fires. The Sidebar and work-item pages share the same in-flight Promise via the module
  // cache, so this adds no extra network call; it just starts one render cycle sooner.
  useEffect(() => {
    if (!projectId || !accessToken) return
    fetchSidebarWorkflows(projectId, accessToken).catch(() => { /* non-fatal */ })
  }, [projectId, accessToken])

  useEffect(() => {
    if (!projectId) return
    const project = projects.find((p) => p.id === projectId)
    if (!project) return

    if (activeProject?.id !== project.id) {
      setActiveProject(project)
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, projects, activeProject?.id, setActiveProject])

  return <PermissionsProvider projectId={projectId}>{children}</PermissionsProvider>
}
