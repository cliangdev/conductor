'use client'

import { useState, useEffect } from 'react'
import { X, ArrowLeft, RotateCcw } from 'lucide-react'
import { listVersions, getDocVersion, restoreVersion } from '@/lib/docs-api'
import type { DocVersion, ProjectDoc } from '@/lib/docs-api'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'

export interface DocHistoryPanelProps {
  projectId: string
  docId: string
  token: string
  onClose: () => void
  onRestored: (updatedDoc: ProjectDoc) => void
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

export function DocHistoryPanel({
  projectId,
  docId,
  token,
  onClose,
  onRestored,
}: DocHistoryPanelProps) {
  const [versions, setVersions] = useState<DocVersion[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [viewingVersion, setViewingVersion] = useState<DocVersion | null>(null)
  const [loadingVersion, setLoadingVersion] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const [confirmVersion, setConfirmVersion] = useState<DocVersion | null>(null)

  useEffect(() => {
    listVersions(projectId, docId, token)
      .then(setVersions)
      .catch((err) => setError(err instanceof Error ? err.message : 'Failed to load versions'))
      .finally(() => setLoading(false))
  }, [projectId, docId, token])

  const maxVersionNumber = versions.length > 0 ? Math.max(...versions.map((v) => v.versionNumber)) : 0

  async function handleView(version: DocVersion) {
    setLoadingVersion(true)
    try {
      const full = await getDocVersion(projectId, docId, version.id, token)
      setViewingVersion(full)
    } catch {
      setError('Failed to load version content')
    } finally {
      setLoadingVersion(false)
    }
  }

  async function handleRestore(version: DocVersion) {
    setConfirmVersion(version)
  }

  async function confirmRestore() {
    if (!confirmVersion) return
    setRestoring(true)
    setConfirmVersion(null)
    try {
      const updated = await restoreVersion(projectId, docId, confirmVersion.id, token)
      onRestored(updated)
      onClose()
    } catch {
      setError('Failed to restore version')
    } finally {
      setRestoring(false)
    }
  }

  if (viewingVersion) {
    return (
      <div className="absolute inset-0 z-20 flex flex-col bg-background overflow-hidden">
        <div className="flex items-center justify-between border-b border-border px-4 py-3 shrink-0">
          <button
            onClick={() => setViewingVersion(null)}
            className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            <ArrowLeft className="h-4 w-4" />
            Back to history
          </button>
          <Button
            size="sm"
            variant="outline"
            onClick={() => handleRestore(viewingVersion)}
            disabled={restoring}
          >
            <RotateCcw className="h-3.5 w-3.5 mr-1.5" />
            Restore this version
          </Button>
        </div>

        <div className="shrink-0 flex items-center gap-2 bg-amber-50 dark:bg-amber-950/40 border-b border-amber-200 dark:border-amber-800 px-4 py-2 text-sm text-amber-800 dark:text-amber-300">
          <span>You are viewing version v{viewingVersion.versionNumber} (not current)</span>
        </div>

        <div className="flex-1 overflow-y-auto p-4 md:p-6">
          <MarkdownRenderer content={viewingVersion.content ?? ''} />
        </div>
      </div>
    )
  }

  return (
    <div className="absolute top-0 right-0 bottom-0 z-10 w-56 flex flex-col bg-zinc-900 border-l border-zinc-700 shadow-xl">
      <div className="flex items-center justify-between px-3 py-3 border-b border-zinc-700 shrink-0">
        <span className="text-sm font-semibold text-zinc-100">Version History</span>
        <button
          onClick={onClose}
          className="text-zinc-400 hover:text-zinc-100 transition-colors rounded p-0.5"
          aria-label="Close history panel"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto">
        {loading && (
          <p className="px-3 py-4 text-xs text-zinc-400">Loading...</p>
        )}
        {error && (
          <p className="px-3 py-4 text-xs text-red-400">{error}</p>
        )}
        {!loading && !error && versions.length === 0 && (
          <p className="px-3 py-4 text-xs text-zinc-400">No versions yet.</p>
        )}
        {!loading && !error && versions.map((version) => {
          const isCurrent = version.versionNumber === maxVersionNumber
          return (
            <div
              key={version.id}
              className="px-3 py-2.5 border-b border-zinc-800 last:border-0"
            >
              <div className="flex items-center gap-1.5 mb-0.5">
                <span className="text-xs font-bold text-purple-400">
                  v{version.versionNumber}
                </span>
                {isCurrent && (
                  <span className="text-[10px] px-1 py-0.5 rounded bg-purple-900/60 text-purple-300 font-medium leading-none">
                    Current
                  </span>
                )}
              </div>
              <p className="text-xs text-zinc-300 truncate">{version.authorName}</p>
              <p className="text-[11px] text-zinc-500 mt-0.5">{formatRelativeTime(version.createdAt)}</p>
              {!isCurrent && (
                <div className="flex items-center gap-2 mt-1.5">
                  <button
                    onClick={() => handleView(version)}
                    disabled={loadingVersion}
                    className="text-[11px] text-zinc-400 hover:text-zinc-100 transition-colors disabled:opacity-50"
                  >
                    {loadingVersion ? 'Loading…' : 'View'}
                  </button>
                  <span className="text-zinc-600 text-[11px]">·</span>
                  <button
                    onClick={() => handleRestore(version)}
                    disabled={restoring}
                    className="text-[11px] text-zinc-400 hover:text-zinc-100 transition-colors disabled:opacity-50"
                  >
                    Restore
                  </button>
                </div>
              )}
            </div>
          )
        })}
      </div>

      <Modal
        open={!!confirmVersion}
        onOpenChange={(open) => { if (!open) setConfirmVersion(null) }}
        title="Restore this version?"
        description={`This will replace the current content with version v${confirmVersion?.versionNumber}.`}
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setConfirmVersion(null)} disabled={restoring}>
              Cancel
            </Button>
            <Button onClick={confirmRestore} disabled={restoring}>
              {restoring ? 'Restoring…' : 'Restore'}
            </Button>
          </div>
        }
      >
        <span />
      </Modal>
    </div>
  )
}
