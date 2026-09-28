'use client'

// The Creative library (COND-24 T2) — /app/projects/{id}/marketing/creatives. A static sibling of
// the Area's Workflow list routes, same reasoning as marketing/assets and marketing/insights: a
// Creative is a library object under the Marketing Area, not a Work Item, so it is not scoped to any
// one Workflow (see the PRD's Glossary).

import { useParams } from 'next/navigation'
import { CreativeLibraryGrid } from '@/components/marketing/creatives/CreativeLibraryGrid'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'

export const dynamic = 'force-dynamic'

export default function MarketingCreativesPage() {
  const { projectId } = useParams<{ projectId: string }>()

  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[{ label: 'Marketing' }, { label: 'Creatives' }]}
        title="Creatives"
        description="Photo, headline and layout concepts that render live for every enabled placement."
      />
      <CreativeLibraryGrid projectId={projectId} />
    </PageContainer>
  )
}
