'use client'

import { useEffect, useState } from 'react'
import { X } from 'lucide-react'
import { listKnowledgeRevisions } from '@/lib/knowledge-api'
import type { KnowledgePageRevisionView } from '@/lib/knowledge-api'
import { apiErrorMessage } from '@/lib/api'

export interface KnowledgeHistoryPanelProps {
  projectId: string
  path: string
  token: string
  onClose: () => void
}

function formatRelativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} minute${mins === 1 ? '' : 's'} ago`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `${hrs} hour${hrs === 1 ? '' : 's'} ago`
  const days = Math.floor(hrs / 24)
  return `${days} day${days === 1 ? '' : 's'} ago`
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
    <div className="absolute top-0 right-0 bottom-0 z-10 w-64 flex flex-col bg-zinc-900 border-l border-zinc-700 shadow-xl">
      <div className="flex items-center justify-between px-3 py-3 border-b border-zinc-700 shrink-0">
        <span className="text-sm font-semibold text-zinc-100">History</span>
        <button
          onClick={onClose}
          className="text-zinc-400 hover:text-zinc-100 transition-colors rounded p-0.5"
          aria-label="Close history panel"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto">
        {loading && <p className="px-3 py-4 text-xs text-zinc-400">Loading...</p>}
        {error && <p className="px-3 py-4 text-xs text-red-400">{error}</p>}
        {!loading && !error && revisions.length === 0 && (
          <p className="px-3 py-4 text-xs text-zinc-400">No revisions yet.</p>
        )}
        {!loading &&
          !error &&
          revisions.map((rev) => (
            <div key={rev.version} className="px-3 py-2.5 border-b border-zinc-800 last:border-0">
              <div className="flex items-center gap-1.5 mb-0.5">
                <span className="text-xs font-bold text-purple-400">v{rev.version}</span>
                <span className="text-[10px] px-1 py-0.5 rounded bg-purple-900/60 text-purple-300 font-medium leading-none">
                  {CHANGE_KIND_LABEL[rev.changeKind] ?? rev.changeKind}
                </span>
              </div>
              {rev.actor && (
                <p className="text-xs text-zinc-300 truncate">
                  {rev.actor.kind ?? 'unknown'}
                  {rev.actor.id ? `:${rev.actor.id}` : ''}
                </p>
              )}
              <p className="text-[11px] text-zinc-500 mt-0.5">{formatRelativeTime(rev.createdAt)}</p>
              {rev.sourceRefs && rev.sourceRefs.length > 0 && (
                <div className="flex flex-wrap gap-1 mt-1.5">
                  {rev.sourceRefs.map((ref) => (
                    <span
                      key={ref}
                      className="text-[10px] px-1.5 py-0.5 rounded bg-zinc-800 text-zinc-400 truncate max-w-full"
                      title={ref}
                    >
                      {ref}
                    </span>
                  ))}
                </div>
              )}
            </div>
          ))}
      </div>
    </div>
  )
}
