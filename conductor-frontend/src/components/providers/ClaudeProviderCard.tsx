'use client'

import { useEffect, useState } from 'react'
import { InfoIcon } from 'lucide-react'
import {
  apiErrorMessage,
  deleteProviderCredential,
  listProviderCredentialStatuses,
  setProviderCredential,
} from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import { useCan } from '@/contexts/PermissionsContext'
import { Card, CardHeader, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useToast } from '@/components/ui/toast'

// Two peer methods for the same model provider — either, both, or neither may be connected.
// `claude-code` powers `claude-code` workflow steps and the claude-code agent runtime (billed
// against the subscription, no per-token cost); `claude` is the direct Anthropic API key that
// powers the api agent runtime. Both are stored via the generic provider-credential endpoints.
const CLAUDE_CODE_PROVIDER_ID = 'claude-code'
const CLAUDE_API_PROVIDER_ID = 'claude'

const ROW_COPY: Record<string, { saveSuccess: string; saveFail: string; removeSuccess: string; removeFail: string }> = {
  [CLAUDE_CODE_PROVIDER_ID]: {
    saveSuccess: 'Subscription token saved.',
    saveFail: 'Failed to save subscription token.',
    removeSuccess: 'Subscription token removed.',
    removeFail: 'Failed to remove subscription token.',
  },
  [CLAUDE_API_PROVIDER_ID]: {
    saveSuccess: 'API key saved.',
    saveFail: 'Failed to save API key.',
    removeSuccess: 'API key removed.',
    removeFail: 'Failed to remove API key.',
  },
}

/**
 * The "Connect Claude" surface at Settings → AI Providers: two peer connection methods for the
 * same provider, replacing the two scattered panels (`ClaudeCodeCredentialPanel` under the GCP
 * integration page, `ProviderKeysPanel`'s claude row under Agents → Providers). Both methods can
 * be connected simultaneously — when they are, agents default to the claude-code runtime (no
 * per-token cost) and the API key powers the direct-API runtime.
 */
export function ClaudeProviderCard({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth()
  const canMutate = useCan('agent.manage')
  const { showToast } = useToast()

  const [loading, setLoading] = useState(true)
  const [configured, setConfigured] = useState<Record<string, boolean>>({})
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    if (!accessToken || !projectId) return
    let cancelled = false
    listProviderCredentialStatuses(projectId, accessToken)
      .then((statuses) => {
        if (cancelled) return
        setConfigured(Object.fromEntries(statuses.map((s) => [s.provider, s.configured])))
      })
      .catch(() => {
        if (!cancelled) setConfigured({})
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
    const value = (drafts[providerId] ?? '').trim()
    if (!value) return
    setBusy(providerId)
    try {
      await setProviderCredential(projectId, providerId, value, accessToken)
      setConfigured((prev) => ({ ...prev, [providerId]: true }))
      setDrafts((prev) => ({ ...prev, [providerId]: '' }))
      showToast(ROW_COPY[providerId].saveSuccess, 'success')
    } catch (e) {
      showToast(apiErrorMessage(e, ROW_COPY[providerId].saveFail), 'error')
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
      showToast(ROW_COPY[providerId].removeSuccess, 'success')
    } catch (e) {
      showToast(apiErrorMessage(e, ROW_COPY[providerId].removeFail), 'error')
    } finally {
      setBusy(null)
    }
  }

  function renderRow({
    id,
    label,
    chip,
    description,
    technicalNote,
    placeholder,
  }: {
    id: string
    label: string
    chip?: React.ReactNode
    description: string
    technicalNote?: string
    placeholder: string
  }) {
    const isConfigured = configured[id] ?? false
    return (
      <div key={id} className="px-4 py-4 space-y-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium text-foreground">{label}</span>
          {chip}
          {!loading && (
            isConfigured ? (
              <Badge variant="status-approved">Connected</Badge>
            ) : (
              <Badge variant="outline">Not connected</Badge>
            )
          )}
        </div>
        <p className="text-xs text-muted-foreground">{description}</p>
        {canMutate ? (
          <div className="space-y-1.5">
            {technicalNote && <p className="text-xs text-muted-foreground/80">{technicalNote}</p>}
            <div className="flex gap-2">
              <Input
                type="password"
                value={drafts[id] ?? ''}
                onChange={(e) => setDrafts((prev) => ({ ...prev, [id]: e.target.value }))}
                placeholder={isConfigured ? '•••••••• (set — enter a new value to replace)' : placeholder}
                autoComplete="off"
              />
              <Button
                type="button"
                size="sm"
                aria-label={`${isConfigured ? 'Replace' : 'Save'} ${label}`}
                onClick={() => handleSave(id)}
                disabled={busy === id || !(drafts[id] ?? '').trim()}
              >
                {isConfigured ? 'Replace' : 'Save'}
              </Button>
              {isConfigured && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  aria-label={`Remove ${label}`}
                  onClick={() => handleRemove(id)}
                  disabled={busy === id}
                >
                  Remove
                </Button>
              )}
            </div>
          </div>
        ) : (
          <p className="text-xs text-muted-foreground">Only admins and creators can manage this credential.</p>
        )}
      </div>
    )
  }

  const bothConnected = !loading && (configured[CLAUDE_CODE_PROVIDER_ID] ?? false) && (configured[CLAUDE_API_PROVIDER_ID] ?? false)

  return (
    <Card>
      <CardHeader>
        <div>
          <h2 className="text-sm font-semibold text-foreground">Claude</h2>
          <p className="text-xs text-muted-foreground mt-0.5">
            Two ways to connect — set up one or both.
          </p>
        </div>
      </CardHeader>
      <CardContent>
        {renderRow({
          id: CLAUDE_CODE_PROVIDER_ID,
          label: 'Claude Code subscription',
          chip: <Badge variant="secondary">Recommended</Badge>,
          description: 'Uses your Claude plan — no per-token API cost. Get a token with claude setup-token.',
          technicalNote: 'Paste the output of claude setup-token. Stored encrypted, never displayed after saving.',
          placeholder: 'Enter subscription token',
        })}
        {renderRow({
          id: CLAUDE_API_PROVIDER_ID,
          label: 'Anthropic API key',
          description: 'Bring your own API key, billed per token — powers the direct-API agent runtime.',
          placeholder: 'Enter API key',
        })}
        {bothConnected && (
          <div className="flex items-start gap-2 px-4 py-3 text-xs text-muted-foreground">
            <InfoIcon className="h-3.5 w-3.5 shrink-0 translate-y-0.5" />
            <p>Agents default to Claude Code (cost saving). The API key powers the direct-API runtime.</p>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
