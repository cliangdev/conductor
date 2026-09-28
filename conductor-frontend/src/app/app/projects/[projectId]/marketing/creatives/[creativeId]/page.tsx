'use client'

// The Creative editor (COND-24 T2) — /app/projects/{id}/marketing/creatives/{creativeId}.

import { useParams } from 'next/navigation'
import { CreativeEditor } from '@/components/marketing/creatives/CreativeEditor'
import { PageContainer } from '@/components/layout/PageContainer'
import { Breadcrumb } from '@/components/layout/PageHeader'
import { useAuth } from '@/contexts/AuthContext'

export const dynamic = 'force-dynamic'

export default function CreativeEditorPage() {
  const { projectId, creativeId } = useParams<{ projectId: string; creativeId: string }>()
  const { accessToken } = useAuth()

  return (
    <PageContainer>
      {/* CreativeEditor owns its own title row (displayId, state, kit, version, Save actions) once
          loaded, so this is the breadcrumb only — a second, static "Creative" H1 above it would be
          exactly the duplicate breadcrumb-plus-H1 stack docs/design-system.md rules out. */}
      <Breadcrumb
        items={[{ label: 'Marketing' }, { label: 'Creatives', href: `/app/projects/${projectId}/marketing/creatives` }, { label: 'Editor' }]}
        className="mb-4"
      />
      {accessToken && <CreativeEditor projectId={projectId} creativeId={creativeId} token={accessToken} />}
    </PageContainer>
  )
}
