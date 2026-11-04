'use client'

// A slideshow Creative's text history, newest first: who changed what, from where, and whether it
// applied. Each row lists the words that changed ("Slide 3 · Text 1: old → new"). Read-only; restoring
// an earlier text is not offered. Reloads when the Creative's version moves.

import { useEffect, useState } from 'react'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { apiErrorMessage } from '@/lib/api'
import { timeAgo } from '@/lib/format'
import { cn } from '@/lib/utils'
import { SlideChecksList } from './SlideChecksList'
import { TextChangeRows } from './TextChangeRows'
import { asChecks, listCreativeRevisions, type CreativeRevision, type RevisionSource } from './slideText'

export const SOURCE_LABEL: Record<RevisionSource, string> = {
  EDITOR: 'Web editor',
  REVIEWER: 'Reviewer fix',
  MCP: 'Agent',
  IMPORT: 'Spec stored',
  COMMIT: 'Committed',
}

const STATUS_LABEL = { PENDING: 'Rendering', APPLIED: 'Applied', FAILED: 'Failed' } as const

/** Rows shown before "Show all". */
const PREVIEW_ROWS = 3

function RevisionRow({ revision }: { revision: CreativeRevision }) {
  const [all, setAll] = useState(false)
  const diff = revision.textDiff ?? []
  const shown = all ? diff : diff.slice(0, PREVIEW_ROWS)
  const failedChecks = revision.status === 'FAILED' ? asChecks(revision.checks) : []
  return (
    <li className="space-y-2 px-4 py-3" data-testid={`revision-${revision.revisionNo}`}>
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 text-sm">
        <span className="font-mono text-xs text-muted-foreground">#{revision.revisionNo}</span>
        <span className="font-medium text-foreground">{SOURCE_LABEL[revision.source] ?? revision.source}</span>
        {revision.authorName && <span className="text-muted-foreground">{revision.authorName}</span>}
        <span className="text-xs text-muted-foreground">{timeAgo(revision.createdAt)}</span>
        <span
          className={cn(
            'ml-auto text-xs font-medium',
            revision.status === 'FAILED' ? 'text-status-failed' : revision.status === 'PENDING' ? 'text-status-review' : 'text-muted-foreground',
          )}
          data-testid={`revision-status-${revision.revisionNo}`}
        >
          {STATUS_LABEL[revision.status]}
        </span>
      </div>
      {shown.length > 0 && <TextChangeRows changes={shown} />}
      {diff.length > PREVIEW_ROWS && (
        <button type="button" className="text-xs text-primary hover:underline" onClick={() => setAll((v) => !v)}>
          {all ? 'Show fewer' : `Show all ${diff.length} changes`}
        </button>
      )}
      {revision.status === 'FAILED' && (
        <div className="space-y-1.5">
          {revision.error && <p className="text-sm text-status-failed">{revision.error}</p>}
          {failedChecks.length > 0 && <SlideChecksList checks={failedChecks} withLocation />}
        </div>
      )}
    </li>
  )
}

export function CreativeRevisionsPanel({
  projectId,
  creativeId,
  token,
  version,
}: {
  projectId: string
  creativeId: string
  token: string
  /** The Creative's version; a change reloads the list. */
  version: number
}) {
  const [revisions, setRevisions] = useState<CreativeRevision[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    listCreativeRevisions(projectId, creativeId, token)
      .then((rows) => {
        if (cancelled) return
        setRevisions(rows)
        setError(null)
      })
      .catch((err) => {
        if (cancelled) return
        setRevisions([])
        setError(apiErrorMessage(err, 'Could not load the text history'))
      })
    return () => {
      cancelled = true
    }
  }, [projectId, creativeId, token, version])

  return (
    <Card data-testid="creative-revisions">
      <CardHeader>
        <h3 className="text-sm font-semibold text-foreground">Text history</h3>
      </CardHeader>
      <CardContent>
        {revisions === null ? (
          <div className="space-y-2 p-4">
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-4 w-full" />
          </div>
        ) : error ? (
          <p className="px-4 py-3 text-sm text-destructive">{error}</p>
        ) : revisions.length === 0 ? (
          <p className="px-4 py-3 text-sm text-muted-foreground">No text edits yet.</p>
        ) : (
          <ul className="divide-y divide-border">
            {revisions.map((r) => (
              <RevisionRow key={r.id} revision={r} />
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
