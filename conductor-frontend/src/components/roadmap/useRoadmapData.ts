'use client'

// Fetching shared by the overview and the roadmap: the one delivery-overview call with loading/error state, and the
// status-category / detail-link lookups that need each item's Workflow view.

import { useCallback, useEffect, useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage, apiGet } from '@/lib/api'
import { statusMeta, useWorkflowViewsSettled, workItemDetailPath } from '@/lib/workflows'
import type { Issue } from '@/components/workitems/listTypes'
import type { DeliveryActiveItem, DeliveryOverview } from '@/types/workItem'

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

/** GET /delivery-overview: active items with health and forecast, milestones, and the attention lists. */
export function useDeliveryOverview(projectId: string): Fetched<DeliveryOverview> {
  return useFetched<DeliveryOverview>(`/api/v2/projects/${projectId}/delivery-overview`)
}

/**
 * A roadmap board item from an overview row: the summary's delivery fields plus the row's progress, signals
 * and health. The overview only lists open work, so the shape needs no more than the board and strip read.
 */
export function overviewRowToIssue(row: DeliveryActiveItem): Issue {
  const { item } = row
  const createdAt = item.createdAt ?? ''
  return {
    id: item.id,
    displayId: item.displayId,
    title: item.title,
    type: item.type,
    status: item.status,
    workflow: item.workflow ?? undefined,
    createdAt,
    updatedAt: createdAt,
    priority: item.priority ?? null,
    parentWorkItemId: item.parentWorkItemId ?? null,
    milestoneId: item.milestoneId ?? null,
    milestoneName: item.milestoneName ?? null,
    appetite: item.appetite ?? null,
    appetiteDays: item.appetiteDays ?? null,
    startedAt: item.startedAt ?? null,
    progress: row.progress,
    signals: row.signals,
    health: row.health ?? null,
  }
}

type Views = ReturnType<typeof useWorkflowViewsSettled>['views']

/** The one link rule: an item's detail path from its Workflow's view, null until that view is in. */
function useHrefOf(projectId: string, views: Views) {
  return useCallback(
    (item: { workflow?: string | null; displayId?: string | null }): string | null => {
      const view = item.workflow ? views[item.workflow] : undefined
      if (!view || !item.displayId) return null
      return workItemDetailPath(projectId, view.area ?? 'engineering', view.noun, item.displayId)
    },
    [views, projectId]
  )
}

/** Detail-page links for items that only carry a Workflow slug (null until that Workflow's view loads). */
export function useWorkflowHrefs(projectId: string, slugs: string[]) {
  const { accessToken } = useAuth()
  const { views } = useWorkflowViewsSettled(projectId, slugs, accessToken)
  return useHrefOf(projectId, views)
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
  // The same rule as useWorkflowHrefs, over the views already fetched here.
  const hrefOf = useHrefOf(projectId, views)
  return { categoryOf, hrefOf, ready }
}
