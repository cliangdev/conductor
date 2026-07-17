'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import {
  apiErrorMessage,
  deleteProviderCredential,
  listAgentProviders,
  listProviderCredentialStatuses,
  setProviderCredential,
  type AgentProviderInfo,
} from '@/lib/api'
import { ClaudeProviderCard } from '@/components/providers/ClaudeProviderCard'
import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useToast } from '@/components/ui/toast'
import { settingsBreadcrumbs } from '@/lib/navigation'

const CLAUDE_PROVIDER_ID = 'claude'

/**
 * Settings → AI Providers: the one "Connect Claude" surface. Claude gets the special two-method
 * card ({@link ClaudeProviderCard}); any other model provider `listAgentProviders` returns falls
 * back to a generic single-credential row here (there are none today — `claude-code` is
 * deliberately absent from `listAgentProviders`, it isn't a model an agent can select).
 */
export default function ProvidersSettingsPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const { can } = usePermissions()
  const { showToast } = useToast()
  const canManage = can('agent.manage')

  const [otherProviders, setOtherProviders] = useState<AgentProviderInfo[]>([])
  const [configured, setConfigured] = useState<Record<string, boolean>>({})
  const [loading, setLoading] = useState(true)
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    if (!accessToken || !projectId) return
    let cancelled = false
    Promise.all([
      listAgentProviders(projectId, accessToken),
      listProviderCredentialStatuses(projectId, accessToken),
    ])
      .then(([providers, statuses]) => {
        if (cancelled) return
        setOtherProviders(providers.filter((p) => p.id !== CLAUDE_PROVIDER_ID))
        setConfigured(Object.fromEntries(statuses.map((s) => [s.provider, s.configured])))
      })
      .catch(() => {
        if (!cancelled) setOtherProviders([])
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken])

  async function handleSave(providerId: string) {
    if (!accessToken) return
    const key = (drafts[providerId] ?? '').trim()
    if (!key) return
    setBusy(providerId)
    try {
      await setProviderCredential(projectId, providerId, key, accessToken)
      setConfigured((prev) => ({ ...prev, [providerId]: true }))
      setDrafts((prev) => ({ ...prev, [providerId]: '' }))
      showToast('API key saved.', 'success')
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to save API key.'), 'error')
    } finally {
      setBusy(null)
    }
  }

  async function handleRemove(providerId: string) {
    if (!accessToken) return
    setBusy(providerId)
    try {
      await deleteProviderCredential(projectId, providerId, accessToken)
      setConfigured((prev) => ({ ...prev, [providerId]: false }))
      showToast('API key removed.', 'success')
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to remove API key.'), 'error')
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="AI Providers"
        description="Connect Claude to power your agents and claude-code workflow steps."
        breadcrumbs={settingsBreadcrumbs(projectId, 'settings-providers')}
      />

      <ClaudeProviderCard projectId={projectId} />

      {!loading && otherProviders.length > 0 && (
        <Card>
          <CardContent>
            {otherProviders.map((provider) => {
              const isConfigured = configured[provider.id] ?? false
              return (
                <div key={provider.id} className="flex flex-col gap-2 px-4 py-4">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-foreground">{provider.id}</span>
                    {isConfigured ? (
                      <Badge variant="status-approved">Connected</Badge>
                    ) : (
                      <Badge variant="outline">Not connected</Badge>
                    )}
                  </div>
                  {canManage ? (
                    <div className="flex gap-2">
                      <Input
                        type="password"
                        value={drafts[provider.id] ?? ''}
                        onChange={(e) => setDrafts((prev) => ({ ...prev, [provider.id]: e.target.value }))}
                        placeholder={isConfigured ? '•••••••• (set — enter a new key to replace)' : 'Enter API key'}
                        autoComplete="off"
                      />
                      <Button
                        type="button"
                        size="sm"
                        onClick={() => handleSave(provider.id)}
                        disabled={busy === provider.id || !(drafts[provider.id] ?? '').trim()}
                      >
                        {isConfigured ? 'Replace' : 'Save'}
                      </Button>
                      {isConfigured && (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => handleRemove(provider.id)}
                          disabled={busy === provider.id}
                        >
                          Remove
                        </Button>
                      )}
                    </div>
                  ) : (
                    <p className="text-xs text-muted-foreground">Only admins and creators can manage provider keys.</p>
                  )}
                </div>
              )
            })}
          </CardContent>
        </Card>
      )}
    </div>
  )
}
