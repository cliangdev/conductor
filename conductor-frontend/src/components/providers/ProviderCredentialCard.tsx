'use client'

import { useEffect, useState, type ReactNode } from 'react'
import { Loader2Icon } from 'lucide-react'
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
import { VerificationCheckList } from './VerificationCheckList'

const STALE_AFTER_DAYS = 7

export interface ProviderCredentialRowCopy {
  saveSuccess: string
  saveFail: string
  removeSuccess: string
  removeFail: string
}

/** Handed to a row's `extra` render prop so it can react to (and, via `setVerification`, correct)
 *  this row's own connection state — see {@link ClaudeRuntimeSection}, whose designation change
 *  re-verifies and needs to push the fresh result back into the card's badge. */
export interface ProviderCredentialRowContext {
  configured: boolean
  verification: ProviderVerificationSummary | null
  setVerification: (v: ProviderVerificationSummary | null) => void
}

export interface ProviderCredentialRowConfig {
  id: string
  label: string
  chip?: ReactNode
  description: string
  technicalNote?: string
  honestyNote?: string
  placeholder: string
  copy: ProviderCredentialRowCopy
  extra?: ReactNode | ((ctx: ProviderCredentialRowContext) => ReactNode)
}

export interface ProviderCredentialCardProps {
  projectId: string
  title: string
  subtitle?: string
  rows: ProviderCredentialRowConfig[]
  /** Rendered below all rows, e.g. an info line that only applies once several rows are connected.
   *  Given the latest configured-by-id map so it can decide when to show. */
  footer?: (configured: Record<string, boolean>) => ReactNode
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
 * A "Connect <provider>" card at Settings → AI Providers: one or more peer credential rows for a
 * model provider, each independently connectable. Extracted from the original Claude-only card so
 * OpenAI (and any future BYO-key provider) gets the same three-state verification UX for free —
 * see {@link ClaudeProviderCard} and `OpenAiProviderCard`.
 *
 * Three-state badges make "Connected" trustworthy: Not connected → Connected (stored, unverified)
 * → Verified/Error (a real preflight probe ran — see docs/workflows.md). Verification runs
 * automatically after every save and on demand via the row's Verify button.
 */
export function ProviderCredentialCard({ projectId, title, subtitle, rows, footer }: ProviderCredentialCardProps) {
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

  const copyFor = (id: string) => rows.find((r) => r.id === id)?.copy

  async function handleSave(providerId: string) {
    if (!accessToken) return
    const copy = copyFor(providerId)
    if (!copy) return
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
        showToast(`${copy.saveSuccess} Verification failed: ${status.verification.error ?? 'see details below.'}`, 'error')
      } else if (status.verification?.status === 'verified') {
        showToast(`${copy.saveSuccess} Verified.`, 'success')
      } else {
        showToast(copy.saveSuccess, 'success')
      }
    } catch (e) {
      showToast(apiErrorMessage(e, copy.saveFail), 'error')
    } finally {
      setBusy(null)
    }
  }

  async function handleRemove(providerId: string) {
    if (!accessToken) return
    const copy = copyFor(providerId)
    if (!copy) return
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
      showToast(copy.removeSuccess, 'success')
    } catch (e) {
      showToast(apiErrorMessage(e, copy.removeFail), 'error')
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

  function renderRow(row: ProviderCredentialRowConfig) {
    const { id, label, chip, description, technicalNote, honestyNote, placeholder, extra } = row
    const isConfigured = configured[id] ?? false
    const v = verification[id] ?? null
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
                <VerificationCheckList checks={report.checks} />
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
        {typeof extra === 'function'
          ? extra({
              configured: isConfigured,
              verification: v,
              setVerification: (next) => setVerification((prev) => ({ ...prev, [id]: next })),
            })
          : extra}
      </div>
    )
  }

  return (
    <Card>
      <CardHeader>
        <div>
          <h2 className="text-sm font-semibold text-foreground">{title}</h2>
          {subtitle && <p className="text-xs text-muted-foreground mt-0.5">{subtitle}</p>}
        </div>
      </CardHeader>
      <CardContent>
        {rows.map(renderRow)}
        {!loading && footer?.(configured)}
      </CardContent>
    </Card>
  )
}
