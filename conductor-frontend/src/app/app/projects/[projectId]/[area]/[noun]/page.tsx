'use client'

// The workflow-scoped Work Item list route, e.g. /app/projects/{id}/engineering/issues. The two URL
// segments are the Workflow's `area` and pluralized `noun` (both lowercased); they resolve back to the
// bound Workflow — and its REAL (case-sensitive) slug — via the sidebar workflows list. The slug is then
// passed unchanged to WorkItemListView, which owns all the API calls.

import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { useWorkflowByAreaNoun } from '@/lib/workflows'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { Skeleton } from '@/components/ui/skeleton'
import { WorkItemListView } from '@/components/workitems/WorkItemListView'

export const dynamic = 'force-dynamic'

export default function WorkItemAreaNounPage() {
  const { projectId, area, noun } = useParams<{ projectId: string; area: string; noun: string }>()
  const { accessToken } = useAuth()

  const { status, workflow } = useWorkflowByAreaNoun(projectId, area, noun, accessToken)

  if (status === 'loading') {
    return (
      <PageContainer>
        <Skeleton className="h-9 w-48 mb-6" />
        <div className="space-y-2">
          {[1, 2, 3, 4, 5].map((i) => (
            <Skeleton key={i} className="h-10" style={{ opacity: 1 - i * 0.1 }} />
          ))}
        </div>
      </PageContainer>
    )
  }

  if (status === 'notfound' || !workflow) {
    return (
      <PageContainer>
        <PageHeader title="Work Items" />
        <div className="flex flex-col items-center justify-center h-64 gap-1 text-muted-foreground border border-dashed border-border rounded-lg">
          <span className="font-medium">Workflow not found</span>
          <span className="text-sm">No Workflow matches “{area}/{noun}”.</span>
        </div>
      </PageContainer>
    )
  }

  return (
    <WorkItemListView
      projectId={projectId}
      slug={workflow.slug ?? workflow.name}
      noun={workflow.noun ?? workflow.name}
    />
  )
}
