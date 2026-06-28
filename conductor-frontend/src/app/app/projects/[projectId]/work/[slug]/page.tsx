'use client'

// COND-22: the generic Work Item page. Resolves a Workflow by slug and renders its Work Items in the
// view declared by `default_view` — list today; board/calendar are placeholders (future scope). The
// legacy /issues route redirects here at /work/ENGINEERING.

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { fetchWorkflowView, pluralizeNoun } from '@/lib/workflows'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { WorkItemListView } from '@/components/workitems/WorkItemListView'
import type { WorkflowView } from '@/types/workItem'

export const dynamic = 'force-dynamic'

type LoadState = 'loading' | 'ready' | 'notfound'

function ViewPlaceholder({ title, viewType, noun }: { title: string; viewType: string; noun: string }) {
  return (
    <PageContainer>
      <PageHeader title={title} />
      <div className="flex flex-col items-center justify-center h-64 gap-1 text-muted-foreground border border-dashed border-border rounded-lg">
        <span className="font-medium capitalize">{viewType} view coming soon</span>
        <span className="text-sm">{pluralizeNoun(noun)} will render as a {viewType} here.</span>
      </div>
    </PageContainer>
  )
}

export default function WorkItemPage() {
  const { projectId, slug } = useParams<{ projectId: string; slug: string }>()
  const { accessToken } = useAuth()

  const [view, setView] = useState<WorkflowView | undefined>()
  const [state, setState] = useState<LoadState>('loading')

  useEffect(() => {
    if (!projectId || !slug || !accessToken) return
    let cancelled = false
    setState('loading')
    ;(async () => {
      try {
        const v = await fetchWorkflowView(projectId, slug, accessToken)
        if (!cancelled) {
          setView(v)
          setState('ready')
        }
      } catch {
        // by-slug 404s for an unknown/unbound slug — render the not-found state.
        if (!cancelled) setState('notfound')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [projectId, slug, accessToken])

  if (state === 'loading') {
    return (
      <PageContainer>
        <PageHeader title="Work Items" />
        <div className="flex items-center justify-center h-64 text-muted-foreground">Loading…</div>
      </PageContainer>
    )
  }

  if (state === 'notfound' || !view) {
    return (
      <PageContainer>
        <PageHeader title="Work Items" />
        <div className="flex flex-col items-center justify-center h-64 gap-1 text-muted-foreground border border-dashed border-border rounded-lg">
          <span className="font-medium">Workflow not found</span>
          <span className="text-sm">No Workflow is bound to “{slug}”.</span>
        </div>
      </PageContainer>
    )
  }

  const title = pluralizeNoun(view.noun)

  switch (view.defaultView) {
    case 'board':
      return <ViewPlaceholder title={title} viewType="board" noun={view.noun} />
    case 'calendar':
      return <ViewPlaceholder title={title} viewType="calendar" noun={view.noun} />
    case 'list':
    default:
      return <WorkItemListView projectId={projectId} slug={slug} noun={view.noun} />
  }
}
