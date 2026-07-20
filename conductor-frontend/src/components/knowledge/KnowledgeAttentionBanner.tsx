'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { Alert } from '@/components/ui/alert'
import { Button, buttonVariants } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast'
import { usePermissions } from '@/contexts/PermissionsContext'
import { listWorkflows, listWorkflowRuns } from '@/lib/workflows'
import { retryDeadKnowledgeSources, KNOWLEDGE_LIBRARIAN_SLUG } from '@/lib/knowledge-api'
import { apiErrorMessage } from '@/lib/api'
import { cn } from '@/lib/utils'

/**
 * Attention banner for the Activity page's Inbox tab, shown when there are DEAD sources. Own
 * component (rather than inline in KnowledgeInbox) so a Phase 3 Retry action has a clear, isolated
 * place to land.
 *
 * `deadCount` comes from the parent (already fetched there for the tab's count badge, so this
 * doesn't re-fetch counts). It independently fetches the librarian's most recent run to soften the
 * copy when that run actually succeeded — a `deadCount` above zero doesn't always mean the pipeline
 * is *currently* broken (e.g. a source could still be paused pending manual triage).
 *
 * WorkflowRunDto (the list shape) doesn't expose a run-level error field — only per-step
 * `errorReason` on a fetched run's job detail does. Surfacing that snippet here would mean an extra
 * run-detail fetch per banner render; left as a seam for Phase 3 rather than done speculatively.
 *
 * Admins additionally get a "Retry N sources" action — an ops recovery call (ADMIN-only on the
 * backend too, see `KnowledgeController#retryDeadKnowledgeSources`) that resets every DEAD source
 * back to PENDING. `onRetried` lets the parent (the Activity page's Inbox tab) refetch the count and
 * the inbox list after a successful retry.
 */
export function KnowledgeAttentionBanner({
  projectId,
  token,
  deadCount,
  onRetried,
}: {
  projectId: string
  token: string
  deadCount: number
  onRetried?: () => void
}) {
  const { can } = usePermissions()
  const { showToast } = useToast()
  const isAdmin = can('workspace.manage')
  const [lastRunSucceeded, setLastRunSucceeded] = useState(false)
  const [retrying, setRetrying] = useState(false)

  useEffect(() => {
    if (deadCount === 0) return
    let cancelled = false
    listWorkflows(projectId, token)
      .then((workflows) => {
        const librarian = workflows.find((w) => w.name === KNOWLEDGE_LIBRARIAN_SLUG)
        if (!librarian) return []
        return listWorkflowRuns(projectId, librarian.id, token, { page: 0, size: 1 })
      })
      .then((runs) => {
        if (!cancelled) setLastRunSucceeded(runs?.[0]?.status === 'SUCCESS')
      })
      .catch(() => {
        // Best-effort — falls back to the "runs are failing" framing below, which is the safer
        // default when we can't confirm otherwise.
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token, deadCount])

  if (deadCount === 0) return null

  async function handleRetry() {
    setRetrying(true)
    try {
      const { retried } = await retryDeadKnowledgeSources(projectId, token)
      showToast(`${retried} source${retried === 1 ? '' : 's'} queued for retry`)
      onRetried?.()
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to retry sources'), 'error')
    } finally {
      setRetrying(false)
    }
  }

  const providersHref = `/app/projects/${projectId}/settings/providers`
  const title = lastRunSucceeded
    ? `${deadCount} source${deadCount === 1 ? '' : 's'} couldn't be filed`
    : `${deadCount} source${deadCount === 1 ? '' : 's'} couldn't be filed — the librarian's runs are failing`

  return (
    <Alert variant="destructive">
      <div className="space-y-2">
        <p className="font-medium">{title}</p>
        <p>
          {lastRunSucceeded
            ? 'Fix the Claude credential in Settings → AI Providers, then these sources can be retried.'
            : 'Recent librarian runs failed. Fix the Claude credential in Settings → AI Providers, then these sources can be retried.'}
        </p>
        <div className="flex items-center gap-2">
          <Link href={providersHref} className={cn(buttonVariants({ variant: 'outline', size: 'sm' }), 'bg-surface')}>
            Open AI Providers
          </Link>
          {isAdmin && (
            <Button variant="outline" size="sm" className="bg-surface" onClick={handleRetry} disabled={retrying}>
              {retrying ? 'Retrying…' : `Retry ${deadCount} source${deadCount === 1 ? '' : 's'}`}
            </Button>
          )}
        </div>
      </div>
    </Alert>
  )
}
