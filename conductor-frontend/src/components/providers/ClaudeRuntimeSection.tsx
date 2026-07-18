'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowRightIcon, PlusIcon } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/contexts/AuthContext'
import { useCan } from '@/contexts/PermissionsContext'
import { RuntimeTargetCreateModal } from '@/components/runtime/RuntimeTargetCreateModal'
import { VerificationCheckList } from './VerificationCheckList'
import {
  apiErrorMessage,
  getClaudeRuntime,
  listConnections,
  listRuntimeTargets,
  setClaudeRuntime,
  verifyProviderCredential,
} from '@/lib/api'
import type { ClaudeRuntimeConfig, ConnectionSummary, ProviderVerificationReport, RuntimeTarget } from '@/lib/api'

const BUILTIN_VALUE = ''
const CLAUDE_CODE_PROVIDER_ID = 'claude-code'

/**
 * Settings → AI Providers → Runtime: shows which Cloud Run target `runs-on: cloud-run` resolves to
 * for this project (a designated project target, or the operator's builtin) and lets an admin change
 * the designation — the UI-managed counterpart to `ClaudeRuntimeService`. Renders under the
 * claude-code row in {@link ClaudeProviderCard}.
 */
export function ClaudeRuntimeSection({
  projectId,
  onVerified,
}: {
  projectId: string
  /** Fired with the fresh report after a designation change re-verifies, so the parent card's
   *  claude-code badge updates in place instead of showing the stale (backend-cleared) result. */
  onVerified?: (report: ProviderVerificationReport) => void
}) {
  const { accessToken } = useAuth()
  const canMutate = useCan('agent.manage')

  const [config, setConfig] = useState<ClaudeRuntimeConfig | null>(null)
  const [targets, setTargets] = useState<RuntimeTarget[]>([])
  const [connections, setConnections] = useState<ConnectionSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [verifying, setVerifying] = useState(false)
  const [verifyReport, setVerifyReport] = useState<ProviderVerificationReport | null>(null)

  const [createOpen, setCreateOpen] = useState(false)

  const activeGcpConnections = connections.filter((c) => c.status === 'ACTIVE')

  const load = useCallback(async () => {
    if (!accessToken || !projectId) return
    try {
      const [runtimeConfig, runtimeTargets, gcpConnections] = await Promise.all([
        getClaudeRuntime(projectId, accessToken),
        listRuntimeTargets(projectId, accessToken),
        listConnections(projectId, 'gcp', accessToken),
      ])
      setConfig(runtimeConfig)
      setTargets(runtimeTargets)
      setConnections(gcpConnections)
      setLoadError(null)
    } catch (err) {
      setLoadError(apiErrorMessage(err, 'Failed to load runtime configuration.'))
    } finally {
      setLoading(false)
    }
  }, [accessToken, projectId])

  useEffect(() => { load() }, [load])

  async function handleDesignationChange(targetId: string) {
    if (!accessToken) return
    setSaving(true)
    setSaveError(null)
    setVerifyReport(null)
    try {
      const updated = await setClaudeRuntime(projectId, targetId || null, accessToken)
      setConfig(updated)
      // The designation just changed — the claude-code credential's last verification no longer
      // means anything (the backend already cleared it); re-verify immediately so the provider
      // card's badge reflects the new effective runtime instead of a stale result.
      setVerifying(true)
      try {
        const report = await verifyProviderCredential(projectId, CLAUDE_CODE_PROVIDER_ID, accessToken)
        setVerifyReport(report)
        onVerified?.(report)
      } catch (err) {
        setSaveError(apiErrorMessage(err, 'Runtime saved, but re-verification failed — verify manually above.'))
      } finally {
        setVerifying(false)
      }
    } catch (err) {
      setSaveError(apiErrorMessage(err, 'Failed to update the runtime designation.'))
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <div className="animate-pulse h-16 bg-muted rounded-lg" />
  }

  if (loadError) {
    return <p className="text-sm text-destructive" role="alert">{loadError}</p>
  }

  if (!config) return null

  const target = config.runtimeTarget

  return (
    <div className="px-4 py-4 space-y-3 border-t border-border">
      <div>
        <span className="text-sm font-medium text-foreground">Runtime</span>
        <p className="text-xs text-muted-foreground mt-0.5">
          Where <code className="font-mono">runs-on: cloud-run</code> steps execute for this project.
        </p>
      </div>

      {config.source === 'project-target' && target ? (
        <div className="flex items-center gap-2 flex-wrap text-sm">
          <span className="font-mono text-foreground">{target.name}</span>
          {target.status === 'ACTIVE' ? (
            <Badge variant="status-done">Active</Badge>
          ) : target.status === 'PROVISIONING' ? (
            <Badge variant="secondary">Provisioning</Badge>
          ) : (
            <Badge variant="destructive">Error</Badge>
          )}
        </div>
      ) : (
        <div className="flex items-center gap-2 flex-wrap text-sm">
          <span className="text-foreground">Built-in Conductor runtime</span>
          {config.builtinConfigured ? (
            <Badge variant="status-done">Ready</Badge>
          ) : (
            <Badge variant="destructive">Not configured</Badge>
          )}
        </div>
      )}
      {config.source === 'project-target' && target?.errorMessage && (
        <p className="text-xs text-destructive">{target.errorMessage}</p>
      )}
      {config.source === 'builtin' && !config.builtinConfigured && (
        <p className="text-xs text-muted-foreground">
          No runtime target is designated, and the operator&apos;s built-in Cloud Run target isn&apos;t
          configured either — link a runtime target below, or set GCP_CLOUDRUN_PROJECT_ID on the backend.
        </p>
      )}

      {canMutate ? (
        <div className="space-y-2">
          <div className="flex gap-2 items-center flex-wrap">
            <select
              aria-label="Claude runtime target"
              value={config.runtimeTargetId ?? BUILTIN_VALUE}
              onChange={(e) => handleDesignationChange(e.target.value)}
              disabled={saving || verifying}
              className="rounded-md border border-input bg-background px-3 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
            >
              <option value={BUILTIN_VALUE}>Use built-in</option>
              {targets.map((t) => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </select>
            {(saving || verifying) && (
              <span className="text-xs text-muted-foreground">
                {saving ? 'Saving…' : 'Verifying…'}
              </span>
            )}
            {activeGcpConnections.length > 0 && (
              <Button type="button" variant="outline" size="sm" onClick={() => setCreateOpen(true)}>
                <PlusIcon className="h-3.5 w-3.5 mr-1" />
                New runtime target…
              </Button>
            )}
          </div>

          {activeGcpConnections.length === 0 && (
            <div className="flex items-center gap-1 text-xs text-muted-foreground">
              <span>Connect Google Cloud to link a runtime target.</span>
              <Link
                href={`/app/projects/${projectId}/integrations/gcp`}
                className="inline-flex items-center gap-1 text-primary hover:underline"
              >
                Set up
                <ArrowRightIcon className="h-3 w-3" />
              </Link>
            </div>
          )}

          {saveError && <p className="text-xs text-destructive" role="alert">{saveError}</p>}

          {verifyReport && (
            <details className="text-xs">
              <summary className="cursor-pointer text-muted-foreground">
                Re-verification: {verifyReport.status === 'verified' ? 'Verified' : 'Error'}
              </summary>
              <VerificationCheckList checks={verifyReport.checks} />
            </details>
          )}
        </div>
      ) : (
        <p className="text-xs text-muted-foreground">Only admins and creators can change the runtime target.</p>
      )}

      <RuntimeTargetCreateModal
        projectId={projectId}
        connections={activeGcpConnections}
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(created) => setTargets((prev) => [...prev, created])}
      />
    </div>
  )
}
