'use client'

// Roadmap — /app/projects/{id}/roadmap. Active PRDs on a milestone timeline and bucketed Now / Next / Later.

import { useParams } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { RoadmapPanel } from '@/components/roadmap/RoadmapPanel'

export const dynamic = 'force-dynamic'

export default function RoadmapPage() {
  const { projectId } = useParams<{ projectId: string }>()
  return (
    <PageContainer>
      <PageHeader title="Roadmap" description="What is being worked on now, what is next, and what can wait." />
      <RoadmapPanel projectId={projectId} />
    </PageContainer>
  )
}
