'use client'

// Resolves a Work Item id (a parent, here) to what a link to it needs: its display id, title and detail
// path. The path needs the item's Workflow area/noun, so it starts as the UUID redirect route (which
// always lands) and upgrades to the readable path once the Workflow view is in.

import { useEffect, useState } from 'react'
import { apiGet } from '@/lib/api'
import { fetchWorkflowView, workItemDetailPath } from '@/lib/workflows'

export interface WorkItemRef {
  id: string
  displayId: string
  title: string
  href: string
}

export function useWorkItemRef(projectId: string, workItemId: string | null | undefined, token: string): WorkItemRef | null {
  const [ref, setRef] = useState<WorkItemRef | null>(null)

  useEffect(() => {
    if (!workItemId || !token) {
      setRef(null)
      return
    }
    let cancelled = false
    const redirect = `/app/projects/${projectId}/issues/${workItemId}`
    apiGet<{ id: string; displayId: string; title: string; workflow?: string }>(
      `/api/v2/projects/${projectId}/work-items/${workItemId}`,
      token
    )
      .then(async (item) => {
        if (cancelled) return
        const base = { id: item.id, displayId: item.displayId, title: item.title }
        setRef({ ...base, href: redirect })
        if (!item.workflow) return
        try {
          const view = await fetchWorkflowView(projectId, item.workflow, token)
          if (cancelled) return
          setRef({ ...base, href: workItemDetailPath(projectId, view.area ?? item.workflow, view.noun, item.displayId) })
        } catch {
          /* the redirect href already works */
        }
      })
      .catch(() => {
        if (!cancelled) setRef(null)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, workItemId, token])

  return ref
}
