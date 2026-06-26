'use client'

export const dynamic = 'force-dynamic'

import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { ApiKeySection } from '@/components/api-keys/ApiKeySection'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'

export default function ApiKeysPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()

  return (
    <PageContainer width="narrow" className="space-y-6">
      <PageHeader
        breadcrumbs={[
          { label: 'Settings', href: `/app/projects/${projectId}/settings/general` },
          { label: 'API Keys' },
        ]}
        title="API Keys"
        description="Personal API keys for the Conductor CLI and integrations. These are tied to your account, not the workspace."
      />
      <ApiKeySection accessToken={accessToken} />
    </PageContainer>
  )
}
