'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import {
  apiErrorMessage,
  createWorkflowSecret,
  deleteWorkflowSecret,
  listWorkflowSecrets,
  updateWorkflowSecret,
  type WorkflowSecretKey,
} from '@/lib/api'
import { settingsBreadcrumbs } from '@/lib/navigation'
import { PageHeader } from '@/components/layout/PageHeader'

const KEY_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/

export default function WorkflowSecretsPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const { can } = usePermissions()
  const { showToast } = useToast()

  const canManage = can('workflow.manage')

  const [secrets, setSecrets] = useState<WorkflowSecretKey[]>([])
  const [loading, setLoading] = useState(true)

  const [addOpen, setAddOpen] = useState(false)
  const [addKey, setAddKey] = useState('')
  const [addValue, setAddValue] = useState('')
  const [addError, setAddError] = useState<string | null>(null)
  const [addSubmitting, setAddSubmitting] = useState(false)

  const [editingKey, setEditingKey] = useState<string | null>(null)
  const [editValue, setEditValue] = useState('')
  const [editError, setEditError] = useState<string | null>(null)
  const [editSubmitting, setEditSubmitting] = useState(false)

  const [deletingKey, setDeletingKey] = useState<string | null>(null)
  const [deleteSubmitting, setDeleteSubmitting] = useState(false)

  useEffect(() => {
    if (!accessToken || !projectId) return
    listWorkflowSecrets(projectId, accessToken)
      .then(setSecrets)
      .catch((err) => showToast(apiErrorMessage(err, 'Failed to load secrets.'), 'error'))
      .finally(() => setLoading(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, accessToken])

  function openAdd() {
    setAddKey('')
    setAddValue('')
    setAddError(null)
    setAddOpen(true)
  }

  async function handleAdd(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken) return
    const key = addKey.trim().toUpperCase()
    if (!KEY_PATTERN.test(key)) {
      setAddError('Key must be uppercase letters, numbers, and underscores, starting with a letter (e.g. DISCORD_WEBHOOK_URL).')
      return
    }
    if (!addValue) {
      setAddError('Value is required.')
      return
    }
    setAddSubmitting(true)
    setAddError(null)
    try {
      const created = await createWorkflowSecret(projectId, { key, value: addValue }, accessToken)
      setSecrets((prev) => [...prev.filter((s) => s.key !== created.key), created])
      setAddOpen(false)
      showToast('Secret created')
    } catch (err) {
      setAddError(apiErrorMessage(err, 'Failed to create secret.'))
    } finally {
      setAddSubmitting(false)
    }
  }

  function openEdit(key: string) {
    setEditingKey(key)
    setEditValue('')
    setEditError(null)
  }

  async function handleEdit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken || !editingKey) return
    if (!editValue) {
      setEditError('Value is required.')
      return
    }
    setEditSubmitting(true)
    setEditError(null)
    try {
      const updated = await updateWorkflowSecret(projectId, editingKey, editValue, accessToken)
      setSecrets((prev) => prev.map((s) => (s.key === updated.key ? updated : s)))
      setEditingKey(null)
      showToast('Secret updated')
    } catch (err) {
      setEditError(apiErrorMessage(err, 'Failed to update secret.'))
    } finally {
      setEditSubmitting(false)
    }
  }

  async function handleDelete() {
    if (!accessToken || !deletingKey) return
    setDeleteSubmitting(true)
    try {
      await deleteWorkflowSecret(projectId, deletingKey, accessToken)
      setSecrets((prev) => prev.filter((s) => s.key !== deletingKey))
      setDeletingKey(null)
      showToast('Secret deleted')
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to delete secret.'), 'error')
    } finally {
      setDeleteSubmitting(false)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Secrets"
        description="Values workflow YAML can reference by key (e.g. ${{ secrets.DISCORD_WEBHOOK_URL }}). Values are write-only — once set, they're never shown again."
        actions={canManage && <Button size="sm" onClick={openAdd}>Add secret</Button>}
        breadcrumbs={settingsBreadcrumbs(projectId, 'settings-secrets')}
      />

      {loading ? (
        <div className="space-y-2">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : secrets.length === 0 ? (
        <p className="text-sm text-muted-foreground">No secrets yet.</p>
      ) : (
        <Card>
          <CardContent>
            {secrets.map((secret) => (
              <div key={secret.key} className="flex items-center justify-between px-4 py-3">
                <span className="text-sm font-mono">{secret.key}</span>
                {canManage && (
                  <div className="flex items-center gap-1">
                    <Button variant="ghost" size="sm" onClick={() => openEdit(secret.key)}>
                      Update value
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setDeletingKey(secret.key)}
                      className="text-destructive hover:text-destructive hover:bg-destructive/10"
                      aria-label={`Delete secret ${secret.key}`}
                    >
                      Delete
                    </Button>
                  </div>
                )}
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      <Modal open={addOpen} onOpenChange={(o) => { if (!o) setAddOpen(false) }} title="Add secret">
        <form onSubmit={handleAdd} className="space-y-4">
          <div>
            <Label htmlFor="secret-key">Key</Label>
            <Input
              id="secret-key"
              type="text"
              value={addKey}
              onChange={(e) => setAddKey(e.target.value)}
              placeholder="DISCORD_WEBHOOK_URL"
              className="font-mono"
            />
          </div>
          <div>
            <Label htmlFor="secret-value">Value</Label>
            <Input
              id="secret-value"
              type="password"
              value={addValue}
              onChange={(e) => setAddValue(e.target.value)}
            />
          </div>
          {addError && <p className="text-sm text-destructive" role="alert">{addError}</p>}
          <div className="flex gap-3">
            <Button type="submit" disabled={addSubmitting}>
              {addSubmitting ? 'Adding…' : 'Add secret'}
            </Button>
            <Button type="button" variant="outline" onClick={() => setAddOpen(false)} disabled={addSubmitting}>
              Cancel
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={editingKey !== null}
        onOpenChange={(o) => { if (!o) setEditingKey(null) }}
        title={`Update ${editingKey ?? ''}`}
        description="Re-enter the value — the current value cannot be displayed."
      >
        <form onSubmit={handleEdit} className="space-y-4">
          <div>
            <Label htmlFor="secret-edit-value">New value</Label>
            <Input
              id="secret-edit-value"
              type="password"
              value={editValue}
              onChange={(e) => setEditValue(e.target.value)}
            />
          </div>
          {editError && <p className="text-sm text-destructive" role="alert">{editError}</p>}
          <div className="flex gap-3">
            <Button type="submit" disabled={editSubmitting}>
              {editSubmitting ? 'Saving…' : 'Save'}
            </Button>
            <Button type="button" variant="outline" onClick={() => setEditingKey(null)} disabled={editSubmitting}>
              Cancel
            </Button>
          </div>
        </form>
      </Modal>

      <Modal
        open={deletingKey !== null}
        onOpenChange={(o) => { if (!o) setDeletingKey(null) }}
        title="Delete secret"
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{deletingKey}</strong>? Workflows referencing it will fail until replaced.
        </p>
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleDelete} disabled={deleteSubmitting}>
            {deleteSubmitting ? 'Deleting…' : 'Delete'}
          </Button>
          <Button variant="outline" onClick={() => setDeletingKey(null)} disabled={deleteSubmitting}>
            Cancel
          </Button>
        </div>
      </Modal>
    </div>
  )
}
