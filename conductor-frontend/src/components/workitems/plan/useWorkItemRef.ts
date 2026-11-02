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

export type ParentRefStatus = 'loading' | 'ready' | 'error'

export interface WorkItemRefState {
  status: ParentRefStatus
  /** Set once `status` is `ready` and an id was asked for. */
  ref: WorkItemRef | null
}

/** No id to resolve: nothing to wait for and nothing to show. */
export const NO_REF: WorkItemRefState = { status: 'ready', ref: null }

export function useWorkItemRef(projectId: string, workItemId: string | null | undefined, token: string): WorkItemRefState {
  // The key a result belongs to, so a result for a previous id is never shown for the current one.
  const key = workItemId && token ? `${projectId}:${workItemId}` : null
  const [result, setResult] = useState<{ key: string; ref: WorkItemRef | null; failed: boolean } | null>(null)

  useEffect(() => {
    if (!key || !workItemId) return
    let cancelled = false
    const redirect = `/app/projects/${projectId}/issues/${workItemId}`
    apiGet<{ id: string; displayId: string; title: string; workflow?: string }>(
      `/api/v2/projects/${projectId}/work-items/${workItemId}`,
      token
    )
      .then(async (item) => {
        if (cancelled) return
        const base = { id: item.id, displayId: item.displayId, title: item.title }
        setResult({ key, ref: { ...base, href: redirect }, failed: false })
        if (!item.workflow) return
        try {
          const view = await fetchWorkflowView(projectId, item.workflow, token)
          if (cancelled) return
          setResult({
            key,
            ref: { ...base, href: workItemDetailPath(projectId, view.area ?? item.workflow, view.noun, item.displayId) },
            failed: false,
          })
        } catch {
          /* the redirect href already works */
        }
      })
      .catch(() => {
        if (!cancelled) setResult({ key, ref: null, failed: true })
      })
    return () => {
      cancelled = true
    }
  }, [key, projectId, workItemId, token])

  if (!workItemId) return NO_REF
  if (!key || !result || result.key !== key) return { status: 'loading', ref: null }
  return result.failed ? { status: 'error', ref: null } : { status: 'ready', ref: result.ref }
}
