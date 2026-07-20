'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { SettingsIcon } from 'lucide-react'
import { usePermissions } from '@/contexts/PermissionsContext'
import { StatusBadge } from '@/components/ui/status-badge'
import {
  getKnowledgeSourceCounts,
  listKnowledgeDomains,
  KNOWLEDGE_LIBRARIAN_SLUG,
  type KnowledgeSourceCounts,
} from '@/lib/knowledge-api'
import { listWorkflows, listWorkflowRuns } from '@/lib/workflows'

type HealthState = 'needs-attention' | 'working' | 'up-to-date' | 'waiting-for-sources'

interface HealthData {
  counts: KnowledgeSourceCounts
  lastRunFailed: boolean
}

/**
 * Rail footer, pinned to the bottom of the Knowledge nav: a one-chip pipeline health summary and,
 * for admins, the entry point into the Manage registry. The health chip and the Manage badge are
 * independent best-effort fetches (a fetch failure omits just that segment) so a counts outage doesn't also blank the
 * Manage entry. Together they're at most 3-4 calls, and the domains call only runs for admins.
 *
 * `hasContent` (whether the wiki has any content pages, from the layout's own index parse — see
 * KnowledgeLayout) lets the chip tell a brand-new, never-touched wiki apart from one that's
 * genuinely caught up: both look like "all counts zero" from the pipeline's point of view.
 */
export function KnowledgeRailFooter({
  projectId,
  token,
  hasContent,
}: {
  projectId: string
  token: string
  hasContent?: boolean
}) {
  const { can } = usePermissions()
  const isAdmin = can('workspace.manage')

  const [health, setHealth] = useState<HealthData | null>(null)
  const [suggestedCount, setSuggestedCount] = useState(0)

  useEffect(() => {
    let cancelled = false

    async function loadHealth(): Promise<HealthData> {
      const counts = await getKnowledgeSourceCounts(projectId, token)

      let lastRunFailed = false
      try {
        const workflows = await listWorkflows(projectId, token)
        const librarian = workflows.find((w) => w.name === KNOWLEDGE_LIBRARIAN_SLUG)
        if (librarian) {
          const runs = await listWorkflowRuns(projectId, librarian.id, token, { page: 0, size: 1 })
          lastRunFailed = runs[0]?.status === 'FAILED'
        }
      } catch {
        // Run history is a nice-to-have for the health chip — fall back to counts-only.
      }

      return { counts, lastRunFailed }
    }

    loadHealth()
      .then((result) => {
        if (!cancelled) setHealth(result)
      })
      .catch(() => {
        if (!cancelled) setHealth(null)
      })

    return () => {
      cancelled = true
    }
  }, [projectId, token])

  useEffect(() => {
    if (!isAdmin) return
    let cancelled = false
    listKnowledgeDomains(projectId, token)
      .then((domains) => {
        if (!cancelled) setSuggestedCount(domains.filter((d) => d.state === 'SUGGESTED').length)
      })
      .catch(() => {
        if (!cancelled) setSuggestedCount(0)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token, isAdmin])

  return (
    <div className="mt-auto border-t border-sidebar-border px-1 py-1 space-y-0.5">
      {health && <HealthChip projectId={projectId} data={health} hasContent={hasContent} />}

      {isAdmin && (
        <Link
          href={`/app/projects/${projectId}/knowledge/manage`}
          className="w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-sm text-foreground hover:bg-sidebar-hover transition-colors"
        >
          <SettingsIcon className="h-3.5 w-3.5 shrink-0 opacity-70" />
          Manage
          {suggestedCount > 0 && (
            <span className="ml-auto inline-flex items-center justify-center h-4 min-w-4 px-1 rounded-full bg-status-progress/10 text-status-progress text-[10px] font-medium">
              {suggestedCount}
            </span>
          )}
        </Link>
      )}
    </div>
  )
}

function healthState(data: HealthData, hasContent: boolean | undefined): HealthState {
  if (data.counts.dead > 0 || data.lastRunFailed) return 'needs-attention'
  if (data.counts.processing > 0 || data.counts.pending > 0) return 'working'
  // Nothing has ever been processed and the wiki has no content pages -- a brand-new, untouched
  // project, not one that's genuinely "caught up". hasContent undefined (still loading) falls
  // through to up-to-date rather than flashing this state.
  if (data.counts.processed === 0 && hasContent === false) return 'waiting-for-sources'
  return 'up-to-date'
}

function HealthChip({
  projectId,
  data,
  hasContent,
}: {
  projectId: string
  data: HealthData
  hasContent: boolean | undefined
}) {
  const state = healthState(data, hasContent)
  const href =
    state === 'needs-attention'
      ? `/app/projects/${projectId}/knowledge/activity?tab=inbox&status=DEAD`
      : `/app/projects/${projectId}/knowledge/activity`

  const badge =
    state === 'needs-attention' ? (
      <StatusBadge status="failed" label="Librarian · needs attention" />
    ) : state === 'working' ? (
      <StatusBadge
        status="running"
        label={`Librarian · filing ${data.counts.processing + data.counts.pending} sources…`}
      />
    ) : state === 'waiting-for-sources' ? (
      <StatusBadge status="pending" label="Librarian · waiting for sources" />
    ) : (
      <StatusBadge status="succeeded" label="Librarian · up to date" />
    )

  return (
    <Link href={href} className="block px-2 py-1.5">
      {badge}
    </Link>
  )
}
