'use client'

// The Media library — /app/projects/{id}/marketing/media. The creative media library (images, video,
// audio) as a page of its own, with where each file is used, a static sibling of marketing/creatives: browse, tidy, and turn a
// selection of finished images into a CAROUSEL Creative.

import { useParams } from 'next/navigation'
import { MediaLibrary } from '@/components/marketing/media/MediaLibrary'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { ContentSubNav } from '@/components/marketing/shared/ContentSubNav'

export const dynamic = 'force-dynamic'

export default function MarketingMediaPage() {
  const { projectId } = useParams<{ projectId: string }>()

  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[{ label: 'Marketing' }, { label: 'Media' }]}
        title="Media"
        description="Every image, video and audio file in this workspace — uploads, generated images and finished slides — and where each is used."
      />
      <ContentSubNav projectId={projectId} active="media" />
      <MediaLibrary projectId={projectId} />
    </PageContainer>
  )
}
