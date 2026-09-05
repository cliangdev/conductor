'use client'

import { useEffect, useState } from 'react'
import { CheckCircle2Icon, CircleHelpIcon, XCircleIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardHeader } from '@/components/ui/card'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { StatusBadge, statusHueClasses, type StatusHue } from '@/components/ui/status-badge'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { useCan } from '@/contexts/PermissionsContext'
import {
  apiDelete,
  apiErrorMessage,
  apiGet,
  apiPost,
  apiPut,
  type ConnectorAppCredentialStatus,
  type ConnectorCredentialSource,
  type IntegrationListItem,
} from '@/lib/api'
import { timeAgo } from '@/lib/format'
import { fetchMembersCached } from '@/lib/workflows'
import { cn } from '@/lib/utils'

// The catalog DTOs themselves live with every other API shape in `lib/api.ts`; re-exported here
// so consumers of the panel keep importing the status alongside the component that renders it.
export type { ConnectorAppCredentialStatus, ConnectorCredentialSource }

export interface ConnectorAppCredentialCheck {
  name: string
  status: 'pass' | 'fail' | 'warn'
  message: string
}

export interface ConnectorAppCredentialVerificationReport {
  connectorId: string
  status: 'verified' | 'error' | 'unknown'
  checkedAt: string
  checks: ConnectorAppCredentialCheck[]
}

/**
 * Reads the catalog entry's `appCredential`, which is null for every connector that doesn't use
 * OAuth2 and absent while the catalog is still loading — so every caller gets one nullable value
 * rather than juggling three states.
 */
export function appCredentialOf(
  item: IntegrationListItem | null | undefined
): ConnectorAppCredentialStatus | null {
  return item?.appCredential ?? null
}

// Local vocabulary → status ramp, the same idiom ConnectorFeedsPanel uses for feed states. The
// wording is deliberately about *where the credential lives*, not about app registration: adopting
// a workspace credential is a paste, not a new App Review.
const SOURCE_BADGE: Record<ConnectorCredentialSource, { status: string; label: string }> = {
  PROJECT: { status: 'approved', label: 'Set on this workspace' },
  DEPLOYMENT: { status: 'in_review', label: 'Inherited from the deployment' },
  NONE: { status: 'in_progress', label: 'Not configured' },
}

// `unknown` is a third outcome, never a quiet success or a quiet failure — it gets its own hue,
// icon and headline so "we could not tell" can't be misread as either.
const VERIFY_OUTCOME: Record<
  ConnectorAppCredentialVerificationReport['status'],
  { hue: StatusHue; headline: string; Icon: typeof CheckCircle2Icon }
> = {
  verified: { hue: 'green', headline: 'Credentials verified', Icon: CheckCircle2Icon },
  error: { hue: 'red', headline: 'Credentials rejected', Icon: XCircleIcon },
  unknown: { hue: 'amber', headline: 'Could not determine', Icon: CircleHelpIcon },
}

const CHECK_HUE: Record<ConnectorAppCredentialCheck['status'], StatusHue> = {
  pass: 'green',
  fail: 'red',
  warn: 'amber',
}

function credentialPath(projectId: string, connectorId: string) {
  return `/api/v1/projects/${projectId}/integrations/${connectorId}/app-credentials`
}

interface ConnectorAppCredentialPanelProps {
  projectId: string
  connectorId: string
  /** Display name of the connector, for copy like "no one can connect Meta yet". */
  connectorName: string
  /** The catalog's masked status. The panel is controlled — it hands every change back via `onChange`. */
  status: ConnectorAppCredentialStatus
  onChange: (next: ConnectorAppCredentialStatus) => void
}

/**
 * Readiness and configuration for the platform app a connector's consent flow runs as — shared by
 * the generic connector overview and any connector-specific page that needs it.
 *
 * It exists so a missing app credential is visible *before* someone clicks Connect: without it the
 * gap only surfaces as a server error naming an environment variable, mid-consent. The client
 * secret is write-only: it is sent once on save and never read back, so only its last four
 * characters are ever rendered.
 */
export function ConnectorAppCredentialPanel({
  projectId,
  connectorId,
  connectorName,
  status,
  onChange,
}: ConnectorAppCredentialPanelProps) {
  const { accessToken } = useAuth()
  const { showToast } = useToast()
  // Writing the credential is admin-only (the endpoints return 403 to a CREATOR); running a
  // read-only verification is not.
  const canManage = useCan('integration.appCredential.manage')
  const canVerify = useCan('integration.manage')

  const [editing, setEditing] = useState(false)
  const [clientId, setClientId] = useState('')
  const [clientSecret, setClientSecret] = useState('')
  const [saving, setSaving] = useState(false)
  const [confirmingClear, setConfirmingClear] = useState(false)
  const [clearing, setClearing] = useState(false)
  const [verifying, setVerifying] = useState(false)
  const [report, setReport] = useState<ConnectorAppCredentialVerificationReport | null>(null)
  const [memberNames, setMemberNames] = useState<Record<string, string>>({})

  const source = status.credentialSource
  const badge = SOURCE_BADGE[source]
  // Best-effort: the raw id still shows if the member list can't be read, which beats hiding
  // "who set it" entirely.
  const setterName = status.updatedBy ? (memberNames[status.updatedBy] ?? status.updatedBy) : null

  useEffect(() => {
    const userId = status.updatedBy
    if (source !== 'PROJECT' || !userId || !accessToken) return
    let cancelled = false
    fetchMembersCached(projectId, accessToken)
      .then((list) => {
        const name = list.find((m) => m.userId === userId)?.name
        if (!cancelled && name) setMemberNames((prev) => ({ ...prev, [userId]: name }))
      })
      .catch(() => {
        /* leave the id on screen */
      })
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken, source, status.updatedBy])

  function openForm() {
    // Only carry over an id this workspace already owns — prefilling the deployment's would invite
    // saving a workspace row that silently duplicates it.
    setClientId(source === 'PROJECT' ? (status.clientId ?? '') : '')
    setClientSecret('')
    setEditing(true)
  }

  function closeForm() {
    setEditing(false)
    setClientId('')
    setClientSecret('')
  }

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken) return
    setSaving(true)
    try {
      const next = await apiPut<ConnectorAppCredentialStatus>(
        credentialPath(projectId, connectorId),
        { clientId: clientId.trim(), clientSecret },
        accessToken
      )
      // Drop the secret from component state the moment it has been accepted — from here on the
      // only trace of it anywhere in the UI is `clientSecretLast4` off the response.
      closeForm()
      setReport(null)
      onChange(next)
    } catch (err) {
      showToast(apiErrorMessage(err, 'Could not save the credential'), 'error')
    } finally {
      setSaving(false)
    }
  }

  async function handleClear() {
    if (!accessToken) return
    setClearing(true)
    try {
      await apiDelete(credentialPath(projectId, connectorId), accessToken)
      const next = await apiGet<ConnectorAppCredentialStatus>(
        credentialPath(projectId, connectorId),
        accessToken
      )
      setConfirmingClear(false)
      setReport(null)
      onChange(next)
    } catch (err) {
      showToast(apiErrorMessage(err, 'Could not clear the credential'), 'error')
    } finally {
      setClearing(false)
    }
  }

  async function handleVerify() {
    if (!accessToken) return
    setVerifying(true)
    try {
      const next = await apiPost<ConnectorAppCredentialVerificationReport>(
        `${credentialPath(projectId, connectorId)}/verify`,
        {},
        accessToken
      )
      setReport(next)
    } catch (err) {
      showToast(apiErrorMessage(err, 'Could not run verification'), 'error')
    } finally {
      setVerifying(false)
    }
  }

  return (
    <Card className="mb-6 max-w-2xl">
      <CardHeader>
        <h2 className="text-sm font-semibold text-foreground">Platform app credentials</h2>
        <StatusBadge status={badge.status} label={badge.label} />
      </CardHeader>

      <div className="px-4 py-3 space-y-3">
        {editing ? (
          <form onSubmit={handleSave} className="space-y-3">
            <div>
              <Label htmlFor="app-credential-client-id">Client ID</Label>
              <Input
                id="app-credential-client-id"
                value={clientId}
                onChange={(e) => setClientId(e.target.value)}
                autoComplete="off"
              />
            </div>
            <div>
              <Label htmlFor="app-credential-client-secret">Client secret</Label>
              <Input
                id="app-credential-client-secret"
                type="password"
                value={clientSecret}
                onChange={(e) => setClientSecret(e.target.value)}
                autoComplete="new-password"
              />
            </div>
            <p className="text-xs text-muted-foreground">
              Pasting the same client ID and secret another workspace already uses is normal — a
              workspace credential doesn&apos;t need its own app review.
            </p>
            <div className="flex gap-2">
              <Button type="submit" size="sm" disabled={saving || !clientId.trim() || !clientSecret}>
                {saving ? 'Saving…' : 'Save'}
              </Button>
              <Button type="button" size="sm" variant="outline" onClick={closeForm} disabled={saving}>
                Cancel
              </Button>
            </div>
          </form>
        ) : (
          <>
            {source === 'NONE' && (
              <Alert variant="warning">
                <p>No platform app is configured, so nobody can connect {connectorName} yet.</p>
                {/*
                  Two different NONEs need two different instructions. A connector that can inherit the
                  deployment's app has an env var worth naming; one whose app must belong to the
                  workspace does not, and naming one would send an admin to set something nothing reads.
                */}
                {status.allowsDeploymentCredentials ? (
                  status.missingProperties.length > 0 && (
                    <p className="mt-1">
                      Set{' '}
                      {status.missingProperties.map((property, index) => (
                        <span key={property}>
                          {index > 0 && ', '}
                          <code className="font-mono text-xs">{property}</code>
                        </span>
                      ))}{' '}
                      on the deployment, or give this workspace its own credential.
                    </p>
                  )
                ) : (
                  <p className="mt-1">
                    {connectorName} apps belong to the workspace that registered them, so there is no
                    deployment credential to fall back on. Enter this workspace&apos;s client ID and
                    secret below.
                  </p>
                )}
              </Alert>
            )}

            {source === 'DEPLOYMENT' && (
              <p className="text-sm text-muted-foreground">
                Inherited from the deployment — one platform app, shared with every workspace on this
                deployment.
              </p>
            )}

            {source === 'PROJECT' && (
              <p className="text-sm text-muted-foreground">
                Set on this workspace — used instead of the deployment&apos;s credential.
              </p>
            )}

            {status.configured && (
              <dl className="grid grid-cols-[7rem_1fr] gap-x-4 gap-y-1 text-sm">
                <dt className="text-muted-foreground">Client ID</dt>
                <dd className="font-mono text-xs text-foreground break-all">{status.clientId}</dd>
                <dt className="text-muted-foreground">Client secret</dt>
                <dd className="font-mono text-xs text-foreground">
                  {status.clientSecretLast4 ? `••••${status.clientSecretLast4}` : '••••'}
                </dd>
              </dl>
            )}

            {source === 'PROJECT' && status.updatedAt && (
              <p className="text-xs text-muted-foreground">
                Set by {setterName ?? 'someone'} · {timeAgo(status.updatedAt)}
              </p>
            )}

            <div className="flex flex-wrap gap-2">
              {canManage && source === 'PROJECT' && (
                <>
                  <Button size="sm" variant="outline" onClick={openForm}>
                    Replace
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setConfirmingClear(true)}>
                    Clear
                  </Button>
                </>
              )}
              {canManage && source === 'DEPLOYMENT' && (
                <Button size="sm" variant="outline" onClick={openForm}>
                  Use a credential for this workspace instead
                </Button>
              )}
              {canManage && source === 'NONE' && (
                <Button size="sm" onClick={openForm}>
                  Set a credential for this workspace
                </Button>
              )}
              {canVerify && status.configured && (
                <Button size="sm" variant="outline" onClick={handleVerify} disabled={verifying}>
                  {verifying ? 'Verifying…' : 'Verify'}
                </Button>
              )}
            </div>

            {report && <VerificationReport report={report} />}
          </>
        )}
      </div>

      {/*
        Clearing means two different things. Where a deployment app exists the workspace falls back to
        it and connecting keeps working; where it does not, clearing takes the connector offline for
        everyone, so the dialog says so rather than promising a fallback that isn't there.
      */}
      <ConfirmModal
        open={confirmingClear}
        title={
          status.allowsDeploymentCredentials
            ? 'Use the deployment credential?'
            : `Remove this workspace's ${connectorName} app?`
        }
        description={
          status.allowsDeploymentCredentials
            ? `This removes the client ID and secret set on this workspace. ${connectorName} consent flows fall back to whatever the deployment provides.`
            : `This removes the client ID and secret set on this workspace. Nobody can connect ${connectorName} until another app is entered here; connections that already exist keep working until their tokens need refreshing.`
        }
        confirmLabel="Clear"
        busyLabel="Clearing…"
        busy={clearing}
        onConfirm={handleClear}
        onCancel={() => setConfirmingClear(false)}
      />
    </Card>
  )
}

/** The verify report: one of three outcomes, plus every check's message exactly as written. */
function VerificationReport({ report }: { report: ConnectorAppCredentialVerificationReport }) {
  const outcome = VERIFY_OUTCOME[report.status]
  const hue = statusHueClasses(outcome.hue)

  return (
    <div
      role="status"
      data-testid="app-credential-verify-result"
      data-status={report.status}
      className={cn('rounded-md border px-3 py-2.5', hue.border, hue.bg)}
    >
      <div className={cn('flex items-center gap-2 text-sm font-medium', hue.text)}>
        <outcome.Icon className="h-4 w-4 shrink-0" />
        <p>{outcome.headline}</p>
      </div>
      {report.checks.length > 0 && (
        <ul className="mt-2 space-y-1.5">
          {report.checks.map((check) => (
            <li key={check.name} className="flex items-start gap-2 text-xs">
              <span
                className={cn('mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full', statusHueClasses(CHECK_HUE[check.status]).dot)}
              />
              <span className="text-foreground">{check.message}</span>
            </li>
          ))}
        </ul>
      )}
      <p className="mt-2 text-xs text-muted-foreground">Checked {timeAgo(report.checkedAt)}</p>
    </div>
  )
}
