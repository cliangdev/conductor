'use client'

// COND-18: published version history for a lifecycle Workflow. In-flight Work Items stay pinned to
// the version they started on, so older versions remain meaningful even after a new publish.

import { useEffect, useState } from 'react'
import { listWorkflowVersions } from '@/lib/workflows'
import type { WorkflowVersionSummary } from '@/types/workItem'

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString(undefined, {
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
  const [versions, setVersions] = useState<WorkflowVersionSummary[]>([])
  const [loaded, setLoaded] = useState(false)

  useEffect(() => {
    let cancelled = false
    listWorkflowVersions(projectId, workflowId, token)
      .then((data) => {
        if (!cancelled) setVersions(data)
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

  if (versions.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No published versions yet. Publishing a DRAFT records its first version here.
      </p>
    )
  }

  return (
    <ul className="divide-y divide-border border border-border rounded-lg overflow-hidden">
      {versions.map((v) => (
        <li key={v.version} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm">
          <span className="font-medium">Version {v.version}</span>
          <span className="text-muted-foreground text-xs text-right">
            {formatDate(v.publishedAt)}
            {v.publishedBy ? ` · ${v.publishedBy}` : ''}
          </span>
        </li>
      ))}
    </ul>
  )
}
