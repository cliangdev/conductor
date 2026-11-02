'use client'

// Fetching shared by the overview and the roadmap: a list endpoint with loading/error state, and the
// status-category / detail-link lookups that need each item's Workflow view.

import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage, apiGet } from '@/lib/api'
import { fetchMilestones } from '@/lib/delivery'
import { statusMeta, useWorkflowViewsSettled, workItemDetailPath } from '@/lib/workflows'
import type { Issue } from '@/components/workitems/listTypes'
import type { Milestone } from '@/types/workItem'

export interface Fetched<T> {
  data: T | null
  loading: boolean
  error: string | null
}

const LOADING: Fetched<never> = { data: null, loading: true, error: null }

/** GET `path` with the session token. `path` null waits (e.g. before the token is known). */
export function useFetched<T>(path: string | null): Fetched<T> {
  const { accessToken } = useAuth()
  // The result remembers which path it answers, so a new path reads as loading at once and a response
  // for a superseded path can never be shown.
  const [result, setResult] = useState<{ key: string; data: T | null; error: string | null } | null>(null)

  useEffect(() => {
    if (!path || !accessToken) return
    let cancelled = false
    apiGet<T>(path, accessToken)
      .then((data) => !cancelled && setResult({ key: path, data, error: null }))
      .catch((err) => !cancelled && setResult({ key: path, data: null, error: apiErrorMessage(err, 'Failed to load') }))
    return () => {
      cancelled = true
    }
  }, [path, accessToken])

  if (!result || result.key !== path) return LOADING
  return { data: result.data, loading: false, error: result.error }
}

export function workItemsPath(projectId: string, query: string): string {
  return `/api/v2/projects/${projectId}/work-items?${query}`
}

/** The project's milestones; `null` data until loaded. */
export function useMilestones(projectId: string): Fetched<Milestone[]> {
  const { accessToken } = useAuth()
  const [result, setResult] = useState<{ key: string; data: Milestone[] | null; error: string | null } | null>(null)

  useEffect(() => {
    if (!accessToken) return
    let cancelled = false
    fetchMilestones(projectId, accessToken)
      .then((data) => !cancelled && setResult({ key: projectId, data, error: null }))
      .catch((err) => !cancelled && setResult({ key: projectId, data: null, error: apiErrorMessage(err, 'Failed to load milestones') }))
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken])

  if (!result || result.key !== projectId) return LOADING
  return { data: result.data, loading: false, error: result.error }
}

/**
 * Lookups over the Workflows the given items belong to: the status category (open | in_progress |
 * terminal) and the item's detail URL. Both degrade (category `open`, no link) until the views load, so
 * callers render a skeleton until `ready` instead of briefly treating finished items as active.
 */
export function useItemWorkflowLookups(projectId: string, items: Issue[] | null) {
  const { accessToken } = useAuth()
  const slugs = (items ?? []).map((i) => i.workflow).filter((s): s is string => !!s)
  const { views, settled } = useWorkflowViewsSettled(projectId, slugs, accessToken)
  // Ready once the items are in and every Workflow they use has been looked up (or failed to load).
  const ready = items !== null && settled

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
  return { categoryOf, hrefOf, ready }
}
