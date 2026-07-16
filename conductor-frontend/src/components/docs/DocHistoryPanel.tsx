'use client'

import { useState, useEffect } from 'react'
import { X, ArrowLeft, RotateCcw } from 'lucide-react'
import { listVersions, getDocVersion, restoreVersion } from '@/lib/docs-api'
import type { DocVersion, ProjectDoc } from '@/lib/docs-api'
import { apiErrorMessage } from '@/lib/api'
import { timeAgo } from '@/lib/format'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { HistoryListSkeleton } from '@/components/ui/history-list-skeleton'
import { Badge } from '@/components/ui/badge'

export interface DocHistoryPanelProps {
  projectId: string
  docId: string
  token: string
  onClose: () => void
  onRestored: (updatedDoc: ProjectDoc) => void
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
      .catch((err) => setError(apiErrorMessage(err, 'Failed to load versions')))
      .finally(() => setLoading(false))
  }, [projectId, docId, token])

  const maxVersionNumber = versions.length > 0 ? Math.max(...versions.map((v) => v.versionNumber)) : 0

  async function handleView(version: DocVersion) {
    setLoadingVersion(true)
    try {
      const full = await getDocVersion(projectId, docId, version.id, token)
      setViewingVersion(full)
    } catch (err) {
      setError(apiErrorMessage(err, 'Failed to load version content'))
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
    } catch (err) {
      setError(apiErrorMessage(err, 'Failed to restore version'))
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

        <div className="shrink-0 flex items-center gap-2 bg-status-progress/10 border-b border-status-progress/30 px-4 py-2 text-sm text-status-progress">
          <span>You are viewing version v{viewingVersion.versionNumber} (not current)</span>
        </div>

        <div className="flex-1 overflow-y-auto p-4 md:p-6">
          <MarkdownRenderer content={viewingVersion.content ?? ''} />
        </div>
      </div>
    )
  }

  return (
    <div className="absolute top-0 right-0 bottom-0 z-10 w-56 flex flex-col bg-surface-raised border-l border-border shadow-xl">
      <div className="flex items-center justify-between px-3 py-3 border-b border-border shrink-0">
        <span className="text-sm font-semibold text-foreground">Version History</span>
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
        {error && (
          <p className="px-3 py-4 text-xs text-status-failed">{error}</p>
        )}
        {!loading && !error && versions.length === 0 && (
          <p className="px-3 py-4 text-xs text-foreground-subtle">No versions yet.</p>
        )}
        {!loading && !error && versions.map((version) => {
          const isCurrent = version.versionNumber === maxVersionNumber
          return (
            <div
              key={version.id}
              className="px-3 py-2.5 border-b border-border last:border-0"
            >
              <div className="flex items-center gap-1.5 mb-0.5">
                <span className="text-xs font-bold text-status-code-review">
                  v{version.versionNumber}
                </span>
                {isCurrent && <Badge variant="status-code-review">Current</Badge>}
              </div>
              <p className="text-xs text-foreground-muted truncate">{version.authorName}</p>
              <p className="text-[11px] text-foreground-subtle mt-0.5">{timeAgo(version.createdAt)}</p>
              {!isCurrent && (
                <div className="flex items-center gap-2 mt-1.5">
                  <button
                    onClick={() => handleView(version)}
                    disabled={loadingVersion}
                    className="text-[11px] text-foreground-subtle hover:text-foreground transition-colors disabled:opacity-50"
                  >
                    {loadingVersion ? 'Loading…' : 'View'}
                  </button>
                  <span className="text-foreground-subtle text-[11px]">·</span>
                  <button
                    onClick={() => handleRestore(version)}
                    disabled={restoring}
                    className="text-[11px] text-foreground-subtle hover:text-foreground transition-colors disabled:opacity-50"
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
