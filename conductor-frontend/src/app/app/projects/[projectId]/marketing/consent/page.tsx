'use client'

// TikTok consent — /app/projects/{id}/marketing/consent[?request=<id>]. TikTok requires the creator to
// consent personally to every post, so an agent that prepared several sends the person here: one card per
// Post, each with its own choices and its own Confirm. `?request=` scopes the page to one consent request
// (only the person it was created for can open it); with no parameter it lists every post still pending.

import { Suspense } from 'react'
import { useParams, useSearchParams } from 'next/navigation'
import { ConsentPageView } from '@/components/marketing/consent/ConsentPageView'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'

export const dynamic = 'force-dynamic'

function ConsentPageContent() {
  const { projectId } = useParams<{ projectId: string }>()
  const requestId = useSearchParams().get('request')
  return <ConsentPageView projectId={projectId} requestId={requestId} />
}

export default function MarketingConsentPage() {
  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[{ label: 'Marketing' }, { label: 'TikTok consent' }]}
        title="TikTok consent"
        description="TikTok needs you to choose who can see each post and to consent to it yourself. Review each post, then confirm it."
      />
      <Suspense fallback={null}>
        <ConsentPageContent />
      </Suspense>
    </PageContainer>
  )
}
