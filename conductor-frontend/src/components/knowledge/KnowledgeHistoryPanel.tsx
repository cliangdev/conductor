'use client'

import { useEffect, useState } from 'react'
import { X } from 'lucide-react'
import { listKnowledgeRevisions } from '@/lib/knowledge-api'
import type { KnowledgePageRevisionView } from '@/lib/knowledge-api'
import { apiErrorMessage } from '@/lib/api'
import { timeAgo } from '@/lib/format'
import { HistoryListSkeleton } from '@/components/ui/history-list-skeleton'
import { Badge } from '@/components/ui/badge'

export interface KnowledgeHistoryPanelProps {
  projectId: string
  path: string
  token: string
  onClose: () => void
}

const CHANGE_KIND_LABEL: Record<string, string> = {
  CREATE: 'Created',
  UPDATE: 'Updated',
  DELETE: 'Deleted',
}

export function KnowledgeHistoryPanel({ projectId, path, token, onClose }: KnowledgeHistoryPanelProps) {
  const [revisions, setRevisions] = useState<KnowledgePageRevisionView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    listKnowledgeRevisions(projectId, path, token)
      .then(setRevisions)
      .catch((err) => setError(apiErrorMessage(err, 'Failed to load revisions')))
      .finally(() => setLoading(false))
  }, [projectId, path, token])

  return (
    <div className="absolute top-0 right-0 bottom-0 z-10 w-64 flex flex-col bg-surface-raised border-l border-border shadow-xl">
      <div className="flex items-center justify-between px-3 py-3 border-b border-border shrink-0">
        <span className="text-sm font-semibold text-foreground">History</span>
        <button
          onClick={onClose}
          className="text-foreground-subtle hover:text-foreground transition-colors rounded p-0.5"
          aria-label="Close history panel"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto">
        {loading && <HistoryListSkeleton />}
        {error && <p className="px-3 py-4 text-xs text-status-failed">{error}</p>}
        {!loading && !error && revisions.length === 0 && (
          <p className="px-3 py-4 text-xs text-foreground-subtle">No revisions yet.</p>
        )}
        {!loading &&
          !error &&
          revisions.map((rev) => {
            const isDelete = rev.changeKind === 'DELETE'
            return (
              <div key={rev.version} className="px-3 py-2.5 border-b border-border last:border-0">
                <div className="flex items-center gap-1.5 mb-0.5">
                  <span
                    className={
                      isDelete ? 'text-xs font-bold text-status-failed' : 'text-xs font-bold text-status-code-review'
                    }
                  >
                    v{rev.version}
                  </span>
                  <Badge variant={isDelete ? 'status-failed' : 'status-code-review'}>
                    {CHANGE_KIND_LABEL[rev.changeKind] ?? rev.changeKind}
                  </Badge>
                </div>
                {rev.actor && (
                  <p className="text-xs text-foreground-muted truncate">
                    {rev.actor.kind ?? 'unknown'}
                    {rev.actor.id ? `:${rev.actor.id}` : ''}
                  </p>
                )}
                <p className="text-[11px] text-foreground-subtle mt-0.5">{timeAgo(rev.createdAt)}</p>
                {rev.sourceRefs && rev.sourceRefs.length > 0 && (
                  <div className="flex flex-wrap gap-1 mt-1.5">
                    {rev.sourceRefs.map((ref) => (
                      <span
                        key={ref}
                        className="text-[11px] px-1.5 py-0.5 rounded bg-surface-3 text-foreground-subtle truncate max-w-full"
                        title={ref}
                      >
                        {ref}
                      </span>
                    ))}
                  </div>
                )}
              </div>
            )
          })}
      </div>
    </div>
  )
}
