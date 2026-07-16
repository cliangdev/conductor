'use client'

export const dynamic = 'force-dynamic'

import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { ApiKeySection } from '@/components/api-keys/ApiKeySection'
import { PageHeader } from '@/components/layout/PageHeader'
import { settingsBreadcrumbs } from '@/lib/navigation'

export default function ApiKeysPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()

  return (
    <div className="space-y-6">
      <PageHeader
        title="API Keys"
        description="Personal API keys for the Conductor CLI and integrations. These are tied to your account, not the workspace."
        breadcrumbs={settingsBreadcrumbs(projectId, 'settings-api-keys')}
      />
      <ApiKeySection accessToken={accessToken} />
    </div>
  )
}
