'use client'

export const dynamic = 'force-dynamic'

import { useAuth } from '@/contexts/AuthContext'
import { ApiKeySection } from '@/components/api-keys/ApiKeySection'

export default function ApiKeysPage() {
  const { accessToken } = useAuth()

  return (
    <div className="max-w-2xl mx-auto p-6 space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">API Keys</h1>
        <p className="text-muted-foreground mt-1">
          Personal API keys for the Conductor CLI and integrations. These are tied to your account, not the workspace.
        </p>
      </div>
      <ApiKeySection accessToken={accessToken} />
    </div>
  )
}
