'use client'

// Per-item trace drawer (issue #342): given one anchor (a source, page, feed, or webhook event),
// renders the ordered chain of pipeline stages that produced it, oldest first — styled as a vertical
// connected-steps list, the same idiom as KnowledgeHistoryPanel, inside the shared Sheet slide-over
// rather than that panel's hand-rolled absolute positioning (this drawer is opened from pages, like
// Activity, that have no existing `relative` ancestor to anchor an absolute panel to).

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { Sheet } from '@/components/ui/sheet'
import { StatusBadge } from '@/components/ui/status-badge'
import { Alert } from '@/components/ui/alert'
import { HistoryListSkeleton } from '@/components/ui/history-list-skeleton'
import { getPipelineTrace, type PipelineTraceAnchor, type PipelineTraceNode } from '@/lib/knowledge-api'
import { apiErrorMessage } from '@/lib/api'
import { timeAgo } from '@/lib/format'

export interface PipelineTracePanelProps {
  projectId: string
  token: string
  /** null closes the panel and clears any in-flight/loaded trace. */
  anchor: PipelineTraceAnchor | null
  onClose: () => void
}

const STAGE_LABELS: Record<PipelineTraceNode['stage'], string> = {
  WEBHOOKS: 'Webhook',
  FEEDS: 'Connector feed',
  DIGESTS: 'Metrics digest',
  INBOX: 'Knowledge source',
  LIBRARIAN_RUNS: 'Librarian run',
  PAGES_WRITTEN: 'Wiki page',
}

/**
 * Only two of PipelineTraceNode's `link` shapes correspond to an actual frontend route today
 * (`/knowledge/page?path=`, `/integrations/{connectorId}`) — `/workflows/runs/{runId}` (the
 * LIBRARIAN_RUNS node) omits the `workflowId` segment the real run route requires
 * (`/workflows/[workflowId]/runs/[runId]`) and can't be resolved without an extra lookup. Treated
 * as non-navigable for now rather than producing a 404 link; a natural follow-on is either having
 * PipelineTraceService include workflowId, or adding a `/workflows/runs/[runId]` redirect route.
 */
function resolveHref(projectId: string, link: string | null | undefined): string | null {
  if (!link) return null
  if (link.startsWith('/knowledge/page') || link.startsWith('/integrations/')) {
    return `/app/projects/${projectId}${link}`
  }
  return null
}

export function PipelineTracePanel({ projectId, token, anchor, onClose }: PipelineTracePanelProps) {
  const [nodes, setNodes] = useState<PipelineTraceNode[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!anchor) return
    let cancelled = false
    setLoading(true)
    setError(null)
    getPipelineTrace(projectId, anchor, token)
      .then((dto) => {
        if (!cancelled) setNodes(dto.nodes)
      })
      .catch((err) => {
        if (!cancelled) setError(apiErrorMessage(err, 'Failed to load trace'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token, anchor])

  return (
    <Sheet
      open={!!anchor}
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title="Pipeline trace"
      description="How this item moved through the pipeline, oldest step first."
    >
      {loading && <HistoryListSkeleton />}
      {error && <Alert variant="destructive">{error}</Alert>}
      {!loading && !error && nodes.length === 0 && (
        <p className="text-xs text-foreground-subtle">No trace data found for this item.</p>
      )}
      {!loading && !error && nodes.length > 0 && (
        <ol className="space-y-0">
          {nodes.map((node, i) => {
            const href = resolveHref(projectId, node.link)
            const content = (
              <div className="flex-1 min-w-0 pb-4">
                <div className="flex items-center gap-1.5 mb-0.5">
                  <span className="text-xs font-semibold text-foreground">{STAGE_LABELS[node.stage]}</span>
                  {node.degraded ? (
                    <span className="inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium bg-surface-3 text-foreground-subtle">
                      <span className="h-1.5 w-1.5 rounded-full bg-foreground-subtle/50" />
                      Purged by retention
                    </span>
                  ) : (
                    node.status && <StatusBadge status={node.status} />
                  )}
                </div>
                {node.label && <p className="text-xs text-foreground-muted truncate">{node.label}</p>}
                {node.occurredAt && (
                  <p className="text-[11px] text-foreground-subtle mt-0.5">{timeAgo(node.occurredAt)}</p>
                )}
              </div>
            )
            return (
              <li key={`${node.stage}-${node.id}-${i}`} className="relative flex gap-3">
                <div className="flex flex-col items-center pt-0.5">
                  <span
                    className={
                      'h-2.5 w-2.5 shrink-0 rounded-full ' +
                      (node.degraded ? 'bg-foreground-subtle/40' : 'bg-status-approved')
                    }
                  />
                  {i < nodes.length - 1 && <span className="w-px flex-1 bg-border" />}
                </div>
                {href ? (
                  <Link href={href} className="flex-1 min-w-0 hover:opacity-80 transition-opacity">
                    {content}
                  </Link>
                ) : (
                  content
                )}
              </li>
            )
          })}
        </ol>
      )}
    </Sheet>
  )
}
