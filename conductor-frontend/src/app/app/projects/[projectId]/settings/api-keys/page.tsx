'use client'

export const dynamic = 'force-dynamic'

import { useAuth } from '@/contexts/AuthContext'
import { ApiKeySection } from '@/components/api-keys/ApiKeySection'
import { PageHeader } from '@/components/layout/PageHeader'

export default function ApiKeysPage() {
  const { accessToken } = useAuth()

  return (
    <div className="space-y-6">
      <PageHeader
        title="API Keys"
        description="Personal API keys for the Conductor CLI and integrations. These are tied to your account, not the workspace."
      />
      <ApiKeySection accessToken={accessToken} />
    </div>
  )
}
