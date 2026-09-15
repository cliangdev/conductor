'use client'

// The compose route for a publishing Workflow, e.g. /app/projects/{id}/marketing/posts/new. The
// {area}/{noun} segments resolve the bound Workflow the same way the detail route does; a Workflow that
// does not publish has no compose page (its items are created from the list's modal), so this renders
// the not-found state for it rather than a half-page.

import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { useWorkflowByAreaNoun, useWorkflowView } from '@/lib/workflows'
import { WorkItemDetailSkeleton } from '@/components/workitems/WorkItemDetailSkeleton'
import { workflowDeclaresPublishTargets } from '@/components/marketing/destinations/publishState'
import { ComposePostPage } from '@/components/marketing/compose/ComposePostPage'

export const dynamic = 'force-dynamic'

export default function ComposeWorkItemPage() {
  const { projectId, area, noun } = useParams<{ projectId: string; area: string; noun: string }>()
  const { accessToken } = useAuth()
  const { status, workflow } = useWorkflowByAreaNoun(projectId, area, noun, accessToken)
  const slug = workflow?.slug ?? workflow?.name ?? ''
  const view = useWorkflowView(projectId, slug || undefined, accessToken)

  if (status === 'loading' || (status === 'ready' && !view)) {
    return <WorkItemDetailSkeleton />
  }

  if (status === 'notfound' || !workflow || !view || !workflowDeclaresPublishTargets(view)) {
    return (
      <div className="flex h-64 flex-col items-center justify-center gap-1 text-muted-foreground">
        <span className="font-medium">Nothing to compose here</span>
        <span className="text-sm">This Workflow does not publish; create its items from the list.</span>
      </div>
    )
  }

  return (
    <ComposePostPage
      projectId={projectId}
      workflowSlug={slug}
      workflowView={view}
      detailArea={workflow.area ?? area}
      noun={view.noun}
      token={accessToken ?? ''}
    />
  )
}
