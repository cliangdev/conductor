'use client'

import { useState } from 'react'
import { Modal } from '@/components/ui/modal'
import { Button } from '@/components/ui/button'
import { createFolder, renameFolder, createDoc } from '@/lib/docs-api'
import type { DocFolder } from '@/lib/docs-api'
import { apiPatch } from '@/lib/api'

export interface DocFolderDialogProps {
  mode: 'new-folder' | 'rename-folder' | 'new-doc' | 'rename-doc'
  projectId: string
  token: string
  parentFolderId?: string | null
  folderId?: string
  docId?: string
  currentName?: string
  folders?: DocFolder[]
  onSuccess: (newDocId?: string) => void
  onClose: () => void
}

const TITLES: Record<DocFolderDialogProps['mode'], string> = {
  'new-folder': 'New folder',
  'rename-folder': 'Rename folder',
  'new-doc': 'New document',
  'rename-doc': 'Rename document',
}

const LABELS: Record<DocFolderDialogProps['mode'], string> = {
  'new-folder': 'Folder name',
  'rename-folder': 'Folder name',
  'new-doc': 'Document title',
  'rename-doc': 'Document title',
}

export function DocFolderDialog({
  mode,
  projectId,
  token,
  parentFolderId,
  folderId,
  docId,
  currentName,
  folders,
  onSuccess,
  onClose,
}: DocFolderDialogProps) {
  const [value, setValue] = useState(currentName ?? '')
  const [selectedFolderId, setSelectedFolderId] = useState<string>(parentFolderId ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const showFolderPicker = mode === 'new-doc' && folders && folders.length > 0

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const trimmed = value.trim()
    if (!trimmed) return

    setSaving(true)
    setError(null)
    try {
      if (mode === 'new-folder') {
        await createFolder(projectId, trimmed, parentFolderId ?? null, token)
        onSuccess()
      } else if (mode === 'rename-folder' && folderId) {
        await renameFolder(projectId, folderId, trimmed, token)
        onSuccess()
      } else if (mode === 'new-doc') {
        const folderIdToUse = showFolderPicker ? (selectedFolderId || null) : (parentFolderId ?? null)
        const newDoc = await createDoc(projectId, trimmed, folderIdToUse, token)
        onSuccess(newDoc.id)
      } else if (mode === 'rename-doc' && docId) {
        await apiPatch(`/api/v1/projects/${projectId}/docs/${docId}`, { title: trimmed }, token)
        onSuccess()
      }
      onClose()
    } catch {
      setError('Something went wrong. Please try again.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open
      onOpenChange={(open) => { if (!open) onClose() }}
      title={TITLES[mode]}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose} disabled={saving} type="button">
            Cancel
          </Button>
          <Button
            onClick={(e) => handleSubmit(e as unknown as React.FormEvent)}
            disabled={saving || !value.trim()}
            type="button"
          >
            {saving ? 'Saving...' : 'Save'}
          </Button>
        </div>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-3">
        <div className="space-y-1">
          <label htmlFor="dialog-input" className="text-sm font-medium text-foreground">
            {LABELS[mode]}
          </label>
          <input
            id="dialog-input"
            type="text"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            autoFocus
            className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
            placeholder={LABELS[mode]}
          />
        </div>
        {showFolderPicker && (
          <div className="space-y-1">
            <label htmlFor="folder-select" className="text-sm font-medium text-foreground">
              Folder
            </label>
            <select
              id="folder-select"
              value={selectedFolderId}
              onChange={(e) => setSelectedFolderId(e.target.value)}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
            >
              <option value="">No folder</option>
              {folders.map((f) => (
                <option key={f.id} value={f.id}>{f.name}</option>
              ))}
            </select>
          </div>
        )}
        {error && <p className="text-sm text-destructive">{error}</p>}
      </form>
    </Modal>
  )
}
