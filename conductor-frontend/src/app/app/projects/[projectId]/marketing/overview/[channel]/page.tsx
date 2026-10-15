'use client'

// Channel drill-down — /app/projects/{id}/marketing/overview/{channel}.

import { useParams } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { ChannelDetailPanel } from '@/components/marketing/overview/ChannelDetailPanel'

export const dynamic = 'force-dynamic'

const CHANNEL_TITLES: Record<string, string> = {
  web: 'Web',
  search: 'Search',
  social: 'Social',
  store: 'App Store',
  ads: 'Apple Ads',
}

export default function MarketingChannelPage() {
  const { projectId, channel } = useParams<{ projectId: string; channel: string }>()
  const title = CHANNEL_TITLES[channel.toLowerCase()] ?? 'Channel'
  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[
          { label: 'Marketing' },
          { label: 'Overview', href: `/app/projects/${projectId}/marketing/overview` },
          { label: title },
        ]}
        title={title}
      />
      <ChannelDetailPanel projectId={projectId} channel={channel} />
    </PageContainer>
  )
}
