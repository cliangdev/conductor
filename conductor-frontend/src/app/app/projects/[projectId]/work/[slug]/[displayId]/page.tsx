'use client'

// COND-22: the workflow-scoped Work Item detail route, e.g. /work/ENGINEERING/COND-22. The human-readable
// {displayId} is resolved to a Work Item UUID via the canonical /api/v2 by-display endpoint, then the
// UUID-keyed WorkItemDetailView renders the rest. The slug from the URL is the authoritative Workflow.

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet } from '@/lib/api'
import { WorkItemDetailView } from '@/components/workitems/WorkItemDetailView'

export const dynamic = 'force-dynamic'

interface WorkItemResolved {
  id: string
  workflow?: string
  displayId: string
}

type LoadState = 'loading' | 'ready' | 'notfound'

export default function WorkItemDetailPage() {
  const { projectId, slug, displayId } = useParams<{
    projectId: string
    slug: string
    displayId: string
  }>()
  const { accessToken } = useAuth()

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

  if (state === 'loading') {
    return (
      <div className="flex items-center justify-center h-64 text-muted-foreground">
        Loading {displayId}…
      </div>
    )
  }

  if (state === 'notfound' || !workItemId) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-1 text-muted-foreground">
        <span className="font-medium">Work Item not found</span>
        <span className="text-sm">No Work Item with ID “{displayId}” in this Workflow.</span>
      </div>
    )
  }

  return <WorkItemDetailView projectId={projectId} workItemId={workItemId} slug={slug} />
}
