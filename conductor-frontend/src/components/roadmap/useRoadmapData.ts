'use client'

// Fetching shared by the overview and the roadmap: a list endpoint with loading/error state, and the
// status-category / detail-link lookups that need each item's Workflow view.

import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage, apiGet } from '@/lib/api'
import { fetchMilestones } from '@/lib/delivery'
import { statusMeta, useWorkflowViews, workItemDetailPath } from '@/lib/workflows'
import type { Issue } from '@/components/workitems/listTypes'
import type { Milestone } from '@/types/workItem'

export interface Fetched<T> {
  data: T | null
  loading: boolean
  error: string | null
}

/** GET `path` with the session token. `path` null waits (e.g. before the token is known). */
export function useFetched<T>(path: string | null): Fetched<T> {
  const { accessToken } = useAuth()
  const [state, setState] = useState<Fetched<T>>({ data: null, loading: true, error: null })

  useEffect(() => {
    if (!path || !accessToken) return
    let cancelled = false
    apiGet<T>(path, accessToken)
      .then((data) => !cancelled && setState({ data, loading: false, error: null }))
      .catch((err) => !cancelled && setState({ data: null, loading: false, error: apiErrorMessage(err, 'Failed to load') }))
    return () => {
      cancelled = true
    }
  }, [path, accessToken])

  return state
}

export function workItemsPath(projectId: string, query: string): string {
  return `/api/v2/projects/${projectId}/work-items?${query}`
}

/** The project's milestones; `null` data until loaded. */
export function useMilestones(projectId: string): Fetched<Milestone[]> {
  const { accessToken } = useAuth()
  const [state, setState] = useState<Fetched<Milestone[]>>({ data: null, loading: true, error: null })

  useEffect(() => {
    if (!accessToken) return
    let cancelled = false
    fetchMilestones(projectId, accessToken)
      .then((data) => !cancelled && setState({ data, loading: false, error: null }))
      .catch((err) => !cancelled && setState({ data: null, loading: false, error: apiErrorMessage(err, 'Failed to load milestones') }))
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken])

  return state
}

/**
 * Lookups over the Workflows the given items belong to: the status category (open | in_progress |
 * terminal) and the item's detail URL. Both degrade (category `open`, no link) until the views load.
 */
export function useItemWorkflowLookups(projectId: string, items: Issue[] | null) {
  const { accessToken } = useAuth()
  const slugs = (items ?? []).map((i) => i.workflow).filter((s): s is string => !!s)
  const views = useWorkflowViews(projectId, slugs, accessToken)

  const categoryOf = useCallback(
    (item: Issue) => statusMeta(item.workflow ? views[item.workflow] : undefined, item.status).category,
    [views]
  )
  const hrefOf = useCallback(
    (item: Issue): string | null => {
      const view = item.workflow ? views[item.workflow] : undefined
      if (!view || !item.displayId) return null
      return workItemDetailPath(projectId, view.area ?? 'engineering', view.noun, item.displayId)
    },
    [views, projectId]
  )
  return { categoryOf, hrefOf }
}
