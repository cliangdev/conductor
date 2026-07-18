'use client'

import { useEffect, useState } from 'react'
import { InfoIcon, Loader2Icon } from 'lucide-react'
import {
  apiErrorMessage,
  deleteProviderCredential,
  listProviderCredentialStatuses,
  setProviderCredential,
  verifyProviderCredential,
} from '@/lib/api'
import type { ProviderVerificationReport, ProviderVerificationSummary } from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import { useCan } from '@/contexts/PermissionsContext'
import { Card, CardHeader, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useToast } from '@/components/ui/toast'
import { ClaudeRuntimeSection } from './ClaudeRuntimeSection'

// Two peer methods for the same model provider — either, both, or neither may be connected.
// `claude-code` powers `claude-code` workflow steps and the claude-code agent runtime (billed
// against the subscription, no per-token cost); `claude` is the direct Anthropic API key that
// powers the api agent runtime. Both are stored via the generic provider-credential endpoints.
const CLAUDE_CODE_PROVIDER_ID = 'claude-code'
const CLAUDE_API_PROVIDER_ID = 'claude'

const STALE_AFTER_DAYS = 7

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

/** "3m ago"/"2h ago"/"5d ago" — no external date-formatting dependency for a coarse relative label. */
function formatRelative(diffMs: number): string {
  const minutes = Math.floor(diffMs / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  const days = Math.floor(hours / 24)
  return `${days}d ago`
}

function verifiedBadgeLabel(checkedAt: string): string {
  const diffMs = Date.now() - new Date(checkedAt).getTime()
  const days = diffMs / 86_400_000
  const rel = formatRelative(diffMs)
  return days > STALE_AFTER_DAYS ? `Verified · ${rel} (stale)` : `Verified · ${rel}`
}

function firstFailingMessage(report: ProviderVerificationReport): string | undefined {
  return report.checks.find((c) => c.status === 'fail')?.message
}

/**
 * The "Connect Claude" surface at Settings → AI Providers: two peer connection methods for the
 * same provider, replacing the two scattered panels (`ClaudeCodeCredentialPanel` under the GCP
 * integration page, `ProviderKeysPanel`'s claude row under Agents → Providers). Both methods can
 * be connected simultaneously — when they are, agents default to the claude-code runtime (no
 * per-token cost) and the API key powers the direct-API runtime.
 *
 * Three-state badges make "Connected" trustworthy: Not connected → Connected (stored, unverified)
 * → Verified/Error (a real preflight probe ran — see docs/workflows.md). Verification runs
 * automatically after every save and on demand via the row's Verify button.
 */
export function ClaudeProviderCard({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth()
  const canMutate = useCan('agent.manage')
  const { showToast } = useToast()

  const [loading, setLoading] = useState(true)
  const [configured, setConfigured] = useState<Record<string, boolean>>({})
  const [verification, setVerification] = useState<Record<string, ProviderVerificationSummary | null>>({})
  const [reports, setReports] = useState<Record<string, ProviderVerificationReport>>({})
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const [verifying, setVerifying] = useState<string | null>(null)

  useEffect(() => {
    if (!accessToken || !projectId) return
    let cancelled = false
    listProviderCredentialStatuses(projectId, accessToken)
      .then((statuses) => {
        if (cancelled) return
        setConfigured(Object.fromEntries(statuses.map((s) => [s.provider, s.configured])))
        setVerification(Object.fromEntries(statuses.map((s) => [s.provider, s.verification ?? null])))
      })
      .catch(() => {
        if (!cancelled) {
          setConfigured({})
          setVerification({})
        }
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
      const status = await setProviderCredential(projectId, providerId, value, accessToken)
      setConfigured((prev) => ({ ...prev, [providerId]: true }))
      setVerification((prev) => ({ ...prev, [providerId]: status.verification ?? null }))
      setReports((prev) => {
        const next = { ...prev }
        delete next[providerId]
        return next
      })
      setDrafts((prev) => ({ ...prev, [providerId]: '' }))
      if (status.verification?.status === 'error') {
        showToast(
          `${ROW_COPY[providerId].saveSuccess} Verification failed: ${status.verification.error ?? 'see details below.'}`,
          'error',
        )
      } else if (status.verification?.status === 'verified') {
        showToast(`${ROW_COPY[providerId].saveSuccess} Verified.`, 'success')
      } else {
        showToast(ROW_COPY[providerId].saveSuccess, 'success')
      }
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
      setVerification((prev) => ({ ...prev, [providerId]: null }))
      setReports((prev) => {
        const next = { ...prev }
        delete next[providerId]
        return next
      })
      showToast(ROW_COPY[providerId].removeSuccess, 'success')
    } catch (e) {
      showToast(apiErrorMessage(e, ROW_COPY[providerId].removeFail), 'error')
    } finally {
      setBusy(null)
    }
  }

  async function handleVerify(providerId: string) {
    if (!accessToken) return
    setVerifying(providerId)
    try {
      const report = await verifyProviderCredential(projectId, providerId, accessToken)
      setReports((prev) => ({ ...prev, [providerId]: report }))
      setVerification((prev) => ({
        ...prev,
        [providerId]: { status: report.status, checkedAt: report.checkedAt, error: firstFailingMessage(report) },
      }))
      showToast(
        report.status === 'verified' ? 'Verified.' : 'Verification failed — see details below.',
        report.status === 'verified' ? 'success' : 'error',
      )
    } catch (e) {
      showToast(apiErrorMessage(e, 'Verification failed.'), 'error')
    } finally {
      setVerifying(null)
    }
  }

  function renderBadge(id: string) {
    const isConfigured = configured[id] ?? false
    if (!isConfigured) return <Badge variant="outline">Not connected</Badge>
    const v = verification[id]
    if (!v) return <Badge variant="secondary">Connected · not yet verified</Badge>
    if (v.status === 'error') return <Badge variant="destructive">Error</Badge>
    return <Badge variant="status-approved">{verifiedBadgeLabel(v.checkedAt)}</Badge>
  }

  function renderRow({
    id,
    label,
    chip,
    description,
    technicalNote,
    honestyNote,
    placeholder,
  }: {
    id: string
    label: string
    chip?: React.ReactNode
    description: string
    technicalNote?: string
    honestyNote?: string
    placeholder: string
  }) {
    const isConfigured = configured[id] ?? false
    const v = verification[id]
    const report = reports[id]
    return (
      <div key={id} className="px-4 py-4 space-y-2">
        <div className="flex items-center gap-2 flex-wrap">
          <span className="text-sm font-medium text-foreground">{label}</span>
          {chip}
          {!loading && renderBadge(id)}
          {!loading && isConfigured && canMutate && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              aria-label={`Verify ${label}`}
              onClick={() => handleVerify(id)}
              disabled={verifying === id}
              className="h-6 px-2 text-xs"
            >
              {verifying === id ? <Loader2Icon className="h-3 w-3 animate-spin" /> : 'Verify'}
            </Button>
          )}
        </div>
        <p className="text-xs text-muted-foreground">{description}</p>
        {honestyNote && <p className="text-xs text-muted-foreground/80">{honestyNote}</p>}
        {v?.status === 'error' && (
          <div className="text-xs text-destructive">
            {v.error ?? 'Verification failed.'}
            {report && (
              <details className="mt-1">
                <summary className="cursor-pointer text-muted-foreground">Details</summary>
                <ul className="mt-1 space-y-0.5 pl-4 list-disc">
                  {report.checks.map((check) => (
                    <li
                      key={check.name}
                      className={check.status === 'fail' ? 'text-destructive' : 'text-muted-foreground'}
                    >
                      {check.name}: {check.message}
                    </li>
                  ))}
                </ul>
              </details>
            )}
          </div>
        )}
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
        {id === CLAUDE_CODE_PROVIDER_ID && <ClaudeRuntimeSection projectId={projectId} />}
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
          honestyNote: 'Preflight checks runtime configuration and cloud access. Subscription token validity is confirmed on the first run.',
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
