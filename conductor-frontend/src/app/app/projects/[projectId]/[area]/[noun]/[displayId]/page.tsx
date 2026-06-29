'use client'

// The workflow-scoped Work Item detail route, e.g. /app/projects/{id}/engineering/issues/COND-22. The
// {area}/{noun} segments resolve the bound Workflow (for its REAL slug + breadcrumb), and the
// human-readable {displayId} resolves to a Work Item UUID via the canonical /api/v2 by-display endpoint
// (project-scoped — it does not use the slug). The UUID-keyed WorkItemDetailView renders the rest.

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet } from '@/lib/api'
import { useWorkflowByAreaNoun } from '@/lib/workflows'
import { WorkItemDetailView } from '@/components/workitems/WorkItemDetailView'

export const dynamic = 'force-dynamic'

interface WorkItemResolved {
  id: string
  workflow?: string
  displayId: string
}

type LoadState = 'loading' | 'ready' | 'notfound'

export default function WorkItemAreaNounDetailPage() {
  const { projectId, area, noun, displayId } = useParams<{
    projectId: string
    area: string
    noun: string
    displayId: string
  }>()
  const { accessToken } = useAuth()

  const { status: workflowStatus, workflow } = useWorkflowByAreaNoun(projectId, area, noun, accessToken)

  const [workItemId, setWorkItemId] = useState<string | null>(null)
  const [state, setState] = useState<LoadState>('loading')

  useEffect(() => {
    if (!projectId || !displayId || !accessToken) return
    let cancelled = false
    setState('loading')
    ;(async () => {
      try {
        const resolved = await apiGet<WorkItemResolved>(
          `/api/v2/projects/${projectId}/work-items/by-display/${displayId}`,
          accessToken
        )
        if (!cancelled) {
          setWorkItemId(resolved.id)
          setState('ready')
        }
      } catch {
        // by-display 404s for an unknown displayId — render the not-found state.
        if (!cancelled) setState('notfound')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [projectId, displayId, accessToken])

  if (state === 'loading' || workflowStatus === 'loading') {
    return (
      <div className="flex items-center justify-center h-64 text-muted-foreground">
        Loading {displayId}…
      </div>
    )
  }

  if (state === 'notfound' || !workItemId || workflowStatus === 'notfound' || !workflow) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-1 text-muted-foreground">
        <span className="font-medium">Work Item not found</span>
        <span className="text-sm">No Work Item with ID “{displayId}” in this Workflow.</span>
      </div>
    )
  }

  return (
    <WorkItemDetailView
      projectId={projectId}
      workItemId={workItemId}
      slug={workflow.slug ?? workflow.name}
    />
  )
}
