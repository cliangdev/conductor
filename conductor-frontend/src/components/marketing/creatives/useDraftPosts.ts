'use client'

// COND-24 T3: finds the Draft Posts a Creative's render can be attached to. "Post" is not a fixed
// type — any lifecycle Workflow that declares publish targets produces Posts (workflowDeclaresPublishTargets),
// and a project can have more than one. "Draft" is likewise not a literal string: it is whichever
// status each of those Workflows marks `initial: true`. This mirrors WorkItemListView's own fetch
// (`GET /work-items?workflow=<slug>`), just run across every publish-capable Workflow instead of one.

import { useCallback, useEffect, useState } from 'react'
import { apiGet } from '@/lib/api'
import { fetchWorkflowView, listSidebarWorkflows } from '@/lib/workflows'
import { workflowDeclaresPublishTargets } from '@/components/marketing/destinations/publishState'
import type { Issue } from '@/components/workitems/listTypes'
import type { WorkflowView } from '@/types/workItem'

export interface DraftPost {
  id: string
  displayId?: string
  title: string
  workflowSlug: string
  area: string
  noun: string
}

export interface PostWorkflow {
  slug: string
  area: string
  noun: string
}

interface UseDraftPostsResult {
  posts: DraftPost[] | null
  /** Every publish-capable Workflow found, whether or not it currently has a Draft — feeds "New Post". */
  postWorkflows: PostWorkflow[]
  error: string | null
  reload: () => void
}

export function useDraftPosts(projectId: string, token: string | null | undefined): UseDraftPostsResult {
  const [posts, setPosts] = useState<DraftPost[] | null>(null)
  const [postWorkflows, setPostWorkflows] = useState<PostWorkflow[]>([])
  const [error, setError] = useState<string | null>(null)
  const [nonce, setNonce] = useState(0)

  const load = useCallback(async () => {
    if (!projectId || !token) return
    try {
      const workflows = await listSidebarWorkflows(projectId, token)
      const withViews = await Promise.all(
        workflows.map(async (wf) => {
          const slug = wf.slug ?? wf.name
          try {
            const view = await fetchWorkflowView(projectId, slug, token)
            return { wf, view }
          } catch {
            return null
          }
        }),
      )
      const matches = withViews.filter(
        (x): x is { wf: (typeof workflows)[number]; view: WorkflowView } =>
          x !== null && workflowDeclaresPublishTargets(x.view),
      )
      setPostWorkflows(matches.map(({ wf, view }) => ({ slug: view.slug, area: wf.area ?? view.area ?? '', noun: view.noun })))
      const perWorkflow = await Promise.all(
        matches.map(async ({ wf, view }) => {
          const items = await apiGet<Issue[]>(
            `/api/v2/projects/${projectId}/work-items?workflow=${view.slug}`,
            token,
          )
          const initialStatus = view.statuses.find((s) => s.initial)?.id
          return items
            .filter((item) => (initialStatus ? item.status === initialStatus : true))
            .map((item) => ({
              id: item.id,
              displayId: item.displayId,
              title: item.title,
              workflowSlug: view.slug,
              area: wf.area ?? view.area ?? '',
              noun: view.noun,
            }))
        }),
      )
      setPosts(perWorkflow.flat())
      setError(null)
    } catch {
      setPosts([])
      setError('Could not load Posts — please try again.')
    }
  }, [projectId, token])

  useEffect(() => {
    void load()
  }, [load, nonce])

  return { posts, postWorkflows, error, reload: () => setNonce((n) => n + 1) }
}
