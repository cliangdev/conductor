'use client'

// The Marketing asset library (COND-23 T7.2) — /app/projects/{id}/marketing/assets.
//
// A static sibling of the Area's Workflow list routes (`/marketing/posts`, …) rather than a tab
// inside one: the library spans every Workflow in the Area, so it belongs to the Area, not to any
// one Workflow. All the data work lives in AssetLibraryGrid; this route is the page chrome.

import { useParams } from 'next/navigation'
import { AssetLibraryGrid } from '@/components/marketing/AssetLibraryGrid'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'

export const dynamic = 'force-dynamic'

const MARKETING_AREA = 'MARKETING'

export default function MarketingAssetLibraryPage() {
  const { projectId } = useParams<{ projectId: string }>()

  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[{ label: 'Marketing' }, { label: 'Asset Library' }]}
        title="Asset Library"
        description="Every image and video uploaded to a Marketing work item, newest first."
      />
      <AssetLibraryGrid projectId={projectId} area={MARKETING_AREA} />
    </PageContainer>
  )
}
