'use client'

// "What's working" — /app/projects/{id}/marketing/insights. A static sibling of the Area's Workflow
// list routes and the asset library, same reasoning as marketing/assets: insights read across every
// Marketing Workflow, so the route belongs to the Area, not to any one Workflow.

import { Suspense } from 'react'
import { useParams } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { Skeleton } from '@/components/ui/skeleton'
import { WhatsWorkingPanel } from '@/components/marketing/insights/WhatsWorkingPanel'

export const dynamic = 'force-dynamic'

function WhatsWorkingPageContent() {
  const { projectId } = useParams<{ projectId: string }>()

  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[{ label: 'Marketing' }, { label: "What's working" }]}
        title="What's working"
        description="How published Posts are performing: engagement by platform, format and time, and the best and worst destinations."
      />
      <WhatsWorkingPanel projectId={projectId} />
    </PageContainer>
  )
}

export default function MarketingInsightsPage() {
  return (
    <Suspense
      fallback={
        <PageContainer>
          <Skeleton className="mb-6 h-8 w-64" />
          <Skeleton className="h-96 w-full" />
        </PageContainer>
      }
    >
      <WhatsWorkingPageContent />
    </Suspense>
  )
}
