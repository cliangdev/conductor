'use client'

import { useEffect } from 'react'
import { useParams } from 'next/navigation'
import { useProject } from '@/contexts/ProjectContext'
import { useOrg } from '@/contexts/OrgContext'

export default function ProjectLayout({ children }: { children: React.ReactNode }) {
  const { projectId } = useParams<{ projectId: string }>()
  const { projects, activeProject, setActiveProject } = useProject()
  const { orgs, activeOrg, setActiveOrg } = useOrg()

  useEffect(() => {
    if (!projectId) return
    const project = projects.find((p) => p.id === projectId)
    if (!project) return

    if (activeProject?.id !== project.id) {
      setActiveProject(project)
    }

    if (project.orgId && activeOrg?.id !== project.orgId) {
      const org = orgs.find((o) => o.id === project.orgId)
      if (org) setActiveOrg(org)
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, projects, orgs, activeProject?.id, setActiveProject, setActiveOrg])

  return <>{children}</>
}
