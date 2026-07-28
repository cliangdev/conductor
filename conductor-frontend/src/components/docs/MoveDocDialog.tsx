'use client'

import { useEffect, useState } from 'react'
import { Modal } from '@/components/ui/modal'
import { Button } from '@/components/ui/button'
import { getFolders } from '@/lib/docs-api'
import { apiPatch } from '@/lib/api'
import type { DocFolder } from '@/lib/docs-api'

interface MoveDocDialogProps {
  projectId: string
  docId: string
  docTitle: string
  currentFolderId: string | null
  token: string
  onSuccess: () => void
  onClose: () => void
}

export function MoveDocDialog({
  projectId,
  docId,
  docTitle,
  currentFolderId,
  token,
  onSuccess,
  onClose,
}: MoveDocDialogProps) {
  const [folders, setFolders] = useState<DocFolder[]>([])
  const [loading, setLoading] = useState(true)
  const [selectedFolderId, setSelectedFolderId] = useState<string | null>(currentFolderId)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    getFolders(projectId, token)
      .then(setFolders)
      .catch(() => setError('Failed to load folders.'))
      .finally(() => setLoading(false))
  }, [projectId, token])

  async function handleMove() {
    if (selectedFolderId === currentFolderId) {
      onClose()
      return
    }

    setSaving(true)
    setError(null)
    try {
      // moveToRoot rather than folderId: null — the API can't tell an omitted folderId from an
      // explicit one, so moving out of a folder has to say so.
      const body =
        selectedFolderId === null ? { moveToRoot: true } : { folderId: selectedFolderId }
      await apiPatch(`/api/v1/projects/${projectId}/docs/${docId}`, body, token)
      onSuccess()
      onClose()
    } catch (err: unknown) {
      const status = (err as { status?: number }).status
      if (status === 409) {
        setError(`A document titled "${docTitle}" already exists in the target location.`)
      } else {
        setError('Failed to move document. Please try again.')
      }
    } finally {
      setSaving(false)
    }
  }

  const availableFolders = folders.filter((f) => f.id !== currentFolderId)
  const hasChanged = selectedFolderId !== currentFolderId

  return (
    <Modal
      open
      onOpenChange={(open) => { if (!open) onClose() }}
      title={`Move "${docTitle}"`}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose} disabled={saving} type="button">
            Cancel
          </Button>
          <Button onClick={handleMove} disabled={saving || !hasChanged} type="button">
            {saving ? 'Moving...' : 'Move'}
          </Button>
        </div>
      }
    >
      {loading ? (
        <p className="text-sm text-muted-foreground">Loading folders...</p>
      ) : (
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">Select a destination:</p>
          <div className="space-y-1 max-h-64 overflow-y-auto">
            <label className="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-muted cursor-pointer text-sm">
              <input
                type="radio"
                name="folder"
                value=""
                checked={selectedFolderId === null}
                onChange={() => setSelectedFolderId(null)}
                className="accent-primary"
              />
              <span>Root (no folder)</span>
              {currentFolderId === null && (
                <span className="ml-auto text-xs text-muted-foreground">current</span>
              )}
            </label>
            {availableFolders.map((folder) => (
              <label
                key={folder.id}
                className="flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-muted cursor-pointer text-sm"
              >
                <input
                  type="radio"
                  name="folder"
                  value={folder.id}
                  checked={selectedFolderId === folder.id}
                  onChange={() => setSelectedFolderId(folder.id)}
                  className="accent-primary"
                />
                <span>{folder.name}</span>
              </label>
            ))}
            {availableFolders.length === 0 && currentFolderId === null && (
              <p className="text-xs text-muted-foreground px-2 py-1">No other folders available.</p>
            )}
          </div>
          {error && <p className="text-sm text-destructive">{error}</p>}
        </div>
      )}
    </Modal>
  )
}
