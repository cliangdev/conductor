'use client'

// Marketing overview — /app/projects/{id}/marketing/overview. A static sibling of the Area's other
// Marketing routes (insights, creatives, media): it reads across channels, so it belongs to the Area.

import { useParams } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { OverviewPanel } from '@/components/marketing/overview/OverviewPanel'

export const dynamic = 'force-dynamic'

export default function MarketingOverviewPage() {
  const { projectId } = useParams<{ projectId: string }>()
  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[{ label: 'Marketing' }, { label: 'Overview' }]}
        title="Marketing overview"
        description="Traffic, search, social and ads in one place: how each channel is doing against the period before."
      />
      <OverviewPanel projectId={projectId} />
    </PageContainer>
  )
}
