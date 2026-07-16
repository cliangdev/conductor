'use client'

// COND-18: published version history for a lifecycle Workflow. In-flight Work Items stay pinned to
// the version they started on, so older versions remain meaningful even after a new publish. Each row
// shows the active marker, how many Work Items are pinned to it, and the status diff vs. the prior version.

import { useEffect, useState } from 'react'
import { listWorkflowVersions } from '@/lib/workflows'
import { Badge } from '@/components/ui/badge'
import type { WorkflowVersionsResponse } from '@/types/workItem'

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

export function WorkflowVersionHistory({
  projectId,
  workflowId,
  token,
  /** Bumped by the parent after a publish so the list refetches. */
  refreshKey = 0,
}: {
  projectId: string
  workflowId: string
  token: string
  refreshKey?: number
}) {
  const [data, setData] = useState<WorkflowVersionsResponse | null>(null)
  const [loaded, setLoaded] = useState(false)

  useEffect(() => {
    let cancelled = false
    listWorkflowVersions(projectId, workflowId, token)
      .then((res) => {
        if (!cancelled) setData(res)
      })
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setLoaded(true)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, workflowId, token, refreshKey])

  if (!loaded) {
    return <p className="text-sm text-muted-foreground">Loading version history…</p>
  }

  if (!data || data.versions.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No published versions yet. Publishing a DRAFT records its first version here.
      </p>
    )
  }

  return (
    <div>
      <p className="text-sm text-muted-foreground mb-4">
        {data.activeVersion != null ? `v${data.activeVersion} active` : 'No active version'}{' '}
        · {data.totalWorkItems} total work {data.totalWorkItems === 1 ? 'item' : 'items'}
        {data.versions.length > 0 &&
          ` across ${data.versions.length} ${data.versions.length === 1 ? 'version' : 'versions'}`}
      </p>

      <ul className="divide-y divide-border border border-border rounded-lg overflow-hidden">
        {data.versions.map((v) => (
          <li key={v.version} className="px-4 py-3 text-sm bg-card">
            <div className="flex items-center justify-between gap-3">
              <div className="flex items-center gap-2">
                <span className="font-medium">Version {v.version}</span>
                {v.active && (
                  <Badge variant="status-done" className="text-xs px-1.5 py-0">
                    ACTIVE
                  </Badge>
                )}
              </div>
              <div className="text-right text-xs text-muted-foreground space-y-0.5">
                <div>
                  {formatDate(v.publishedAt)}
                  {v.publishedBy && ` · ${v.publishedBy}`}
                </div>
                <div>
                  {v.workItemCount} work {v.workItemCount === 1 ? 'item' : 'items'}
                </div>
              </div>
            </div>

            {v.changeSummary.statusesAdded.length > 0 ||
            v.changeSummary.statusesRemoved.length > 0 ? (
              <div className="mt-1.5 text-xs space-y-0.5">
                {v.changeSummary.statusesAdded.length > 0 && (
                  <div className="text-status-done">
                    + {v.changeSummary.statusesAdded.join(', ')}
                  </div>
                )}
                {v.changeSummary.statusesRemoved.length > 0 && (
                  <div className="text-status-failed">
                    − {v.changeSummary.statusesRemoved.join(', ')}
                  </div>
                )}
              </div>
            ) : (
              <div className="mt-1.5 text-xs text-muted-foreground">Initial version</div>
            )}
          </li>
        ))}
      </ul>
    </div>
  )
}
