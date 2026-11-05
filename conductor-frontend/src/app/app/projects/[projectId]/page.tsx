'use client'

// Project overview — /app/projects/{id}. Active PRDs, upcoming milestones and what needs attention.

import { useParams } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { OverviewPanel } from '@/components/overview/OverviewPanel'

export const dynamic = 'force-dynamic'

export default function ProjectOverviewPage() {
  const { projectId } = useParams<{ projectId: string }>()
  return (
    <PageContainer>
      <PageHeader title="Overview" description="Where delivery stands: what is in flight, what is due next, and what needs a person." />
      <OverviewPanel projectId={projectId} />
    </PageContainer>
  )
}
