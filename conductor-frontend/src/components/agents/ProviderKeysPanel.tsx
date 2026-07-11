'use client'

import { useEffect, useState } from 'react'
import {
  apiErrorMessage,
  deleteProviderCredential,
  getProviderCredentialStatus,
  listAgentProviders,
  setProviderCredential,
  type AgentProviderInfo,
} from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { useToast } from '@/components/ui/toast'

const INPUT = 'w-full rounded-md border border-input bg-background px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring'

// Not a model provider (agent steps can't select it) — the Claude Code CLI's own subscription
// OAuth token, consumed only by `claude-code` workflow steps. Kept out of `listAgentProviders`
// (that endpoint lists chat-completion model providers), so it's rendered as a fixed extra row
// using the same credential endpoints as the dynamic rows below.
const CLAUDE_CODE_PROVIDER_ID = 'claude-code'

interface ProviderRow {
  provider: AgentProviderInfo
  configured: boolean
}

export function ProviderKeysPanel({
  projectId,
  canMutate,
  roleLoading = false,
}: {
  projectId: string
  canMutate: boolean
  /** True while the viewer's role is still resolving — suppresses the read-only notice flash. */
  roleLoading?: boolean
}) {
  const { accessToken } = useAuth()
  const { showToast } = useToast()
  const [rows, setRows] = useState<ProviderRow[]>([])
  const [claudeCodeConfigured, setClaudeCodeConfigured] = useState(false)
  const [loading, setLoading] = useState(true)
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    if (!accessToken || !projectId) return
    let cancelled = false
    async function load() {
      try {
        const providers = await listAgentProviders(projectId, accessToken!)
        const [statuses, claudeCodeStatus] = await Promise.all([
          Promise.all(
            providers.map((p) =>
              getProviderCredentialStatus(projectId, p.id, accessToken!)
                .then((s) => s.configured)
                .catch(() => false),
            ),
          ),
          getProviderCredentialStatus(projectId, CLAUDE_CODE_PROVIDER_ID, accessToken!)
            .then((s) => s.configured)
            .catch(() => false),
        ])
        if (!cancelled) {
          setRows(providers.map((provider, i) => ({ provider, configured: statuses[i] })))
          setClaudeCodeConfigured(claudeCodeStatus)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    }
    load()
    return () => { cancelled = true }
  }, [projectId, accessToken])

  async function handleSave(providerId: string) {
    if (!accessToken) return
    const key = (drafts[providerId] ?? '').trim()
    if (!key) return
    setBusy(providerId)
    try {
      await setProviderCredential(projectId, providerId, key, accessToken)
      if (providerId === CLAUDE_CODE_PROVIDER_ID) {
        setClaudeCodeConfigured(true)
      } else {
        setRows((prev) => prev.map((r) => (r.provider.id === providerId ? { ...r, configured: true } : r)))
      }
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
      if (providerId === CLAUDE_CODE_PROVIDER_ID) {
        setClaudeCodeConfigured(false)
      } else {
        setRows((prev) => prev.map((r) => (r.provider.id === providerId ? { ...r, configured: false } : r)))
      }
      showToast('API key removed.', 'success')
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to remove API key.'), 'error')
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="bg-card rounded-lg border border-border p-5">
      <h2 className="text-base font-semibold text-foreground">Provider keys</h2>
      <p className="text-sm text-muted-foreground mt-0.5 mb-4">
        Bring-your-own API keys, stored encrypted per provider. Keys are never displayed after saving.
      </p>

      {loading ? (
        <div className="text-sm text-muted-foreground">Loading…</div>
      ) : (
        <div className="space-y-4">
          {rows.length === 0 && (
            <div className="text-sm text-muted-foreground">No model providers are registered.</div>
          )}
          {rows.map(({ provider, configured }) =>
            renderRow({
              id: provider.id,
              label: provider.id,
              configured,
              placeholder: configured ? '•••••••• (set — enter a new key to replace)' : 'Enter API key',
            }),
          )}
          {renderRow({
            id: CLAUDE_CODE_PROVIDER_ID,
            label: 'Claude Code (subscription)',
            configured: claudeCodeConfigured,
            hint: "Paste the output of `claude setup-token` — billed against your Claude Pro/Max plan.",
            placeholder: claudeCodeConfigured
              ? '•••••••• (set — enter a new token to replace)'
              : 'Enter subscription token',
          })}
        </div>
      )}
    </div>
  )

  function renderRow({
    id,
    label,
    configured,
    hint,
    placeholder,
  }: {
    id: string
    label: string
    configured: boolean
    hint?: string
    placeholder: string
  }) {
    return (
      <div key={id} className="flex flex-col gap-2 border-b border-border pb-4 last:border-0 last:pb-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-foreground">{label}</span>
          {configured ? (
            <Badge variant="status-approved">Configured</Badge>
          ) : (
            <Badge variant="outline">Not configured</Badge>
          )}
        </div>
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
        {canMutate ? (
          <div className="flex gap-2">
            <input
              type="password"
              className={INPUT}
              value={drafts[id] ?? ''}
              onChange={(e) => setDrafts((prev) => ({ ...prev, [id]: e.target.value }))}
              placeholder={placeholder}
              autoComplete="off"
            />
            <Button
              type="button"
              onClick={() => handleSave(id)}
              disabled={busy === id || !(drafts[id] ?? '').trim()}
            >
              {configured ? 'Replace' : 'Save'}
            </Button>
            {configured && (
              <Button
                type="button"
                variant="outline"
                onClick={() => handleRemove(id)}
                disabled={busy === id}
              >
                Remove
              </Button>
            )}
          </div>
        ) : roleLoading ? (
          <p className="text-xs text-muted-foreground">Loading…</p>
        ) : (
          <p className="text-xs text-muted-foreground">Only admins and creators can manage provider keys.</p>
        )}
      </div>
    )
  }
}
