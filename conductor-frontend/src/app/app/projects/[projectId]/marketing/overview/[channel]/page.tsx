'use client'

// Channel drill-down — /app/projects/{id}/marketing/overview/{channel}. The panel renders the page
// header itself so the title is the channel's label from the API.

import { useParams } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { ChannelDetailPanel } from '@/components/marketing/overview/ChannelDetailPanel'

export const dynamic = 'force-dynamic'

export default function MarketingChannelPage() {
  const { projectId, channel } = useParams<{ projectId: string; channel: string }>()
  return (
    <PageContainer>
      <ChannelDetailPanel projectId={projectId} channel={channel} />
    </PageContainer>
  )
}
