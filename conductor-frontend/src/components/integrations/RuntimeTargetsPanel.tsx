'use client'

import { useCallback, useEffect, useState } from 'react'
import { Loader2Icon, PlusIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Alert } from '@/components/ui/alert'
import { Modal } from '@/components/ui/modal'
import { RowActionsMenu } from '@/components/ui/RowActionsMenu'
import { RuntimeTargetCreateModal } from '@/components/runtime/RuntimeTargetCreateModal'
import { useCan } from '@/contexts/PermissionsContext'
import { useAuth } from '@/contexts/AuthContext'
import {
  listRuntimeTargets,
  updateRuntimeTarget,
  deleteRuntimeTarget,
  provisionRuntimeTarget,
  apiErrorMessage,
} from '@/lib/api'
import type { ConnectionSummary, RuntimeTarget } from '@/lib/api'

interface EditFormState {
  region: string
  image: string
  jobName: string
}

export default function RuntimeTargetsPanel({
  projectId,
  connections,
}: {
  projectId: string
  connections: ConnectionSummary[]
}) {
  const { accessToken } = useAuth()
  const canMutate = useCan('integration.manage')

  const [targets, setTargets] = useState<RuntimeTarget[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  const activeGcpConnections = connections.filter((c) => c.status === 'ACTIVE')

  const [createOpen, setCreateOpen] = useState(false)

  const [editTarget, setEditTarget] = useState<RuntimeTarget | null>(null)
  const [editForm, setEditForm] = useState<EditFormState>({ region: '', image: '', jobName: '' })
  const [updating, setUpdating] = useState(false)
  const [editError, setEditError] = useState<string | null>(null)

  const [deleteTarget, setDeleteTarget] = useState<RuntimeTarget | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)

  const [retrying, setRetrying] = useState<string | null>(null)

  const fetchTargets = useCallback(async () => {
    if (!accessToken || !projectId) return
    try {
      const data = await listRuntimeTargets(projectId, accessToken)
      setTargets(data)
      setLoadError(null)
    } catch (err) {
      setLoadError(apiErrorMessage(err, 'Failed to load runtime targets.'))
    } finally {
      setLoading(false)
    }
  }, [accessToken, projectId])

  // Re-runs on mount and whenever the parent's connection list changes — the backend flips
  // dependent targets to ERROR when their connection is deleted.
  useEffect(() => { fetchTargets() }, [connections, fetchTargets])

  // Poll while any target is still provisioning — create/update/provision are synchronous on the
  // backend, but this keeps the list honest if another admin triggers a change concurrently.
  useEffect(() => {
    const hasProvisioning = targets.some((t) => t.status === 'PROVISIONING')
    if (!hasProvisioning) return
    const interval = setInterval(fetchTargets, 5000)
    return () => clearInterval(interval)
  }, [targets, fetchTargets])

  function openEditModal(target: RuntimeTarget) {
    setEditTarget(target)
    setEditForm({ region: target.region, image: target.image, jobName: target.jobName })
    setEditError(null)
  }

  async function handleEdit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken || !editTarget) return
    setUpdating(true)
    setEditError(null)
    try {
      const updated = await updateRuntimeTarget(
        projectId,
        editTarget.id,
        { region: editForm.region, image: editForm.image, jobName: editForm.jobName },
        accessToken,
      )
      setTargets((prev) => prev.map((t) => (t.id === updated.id ? updated : t)))
      setEditTarget(null)
    } catch (err) {
      setEditError(apiErrorMessage(err, 'Failed to update runtime target.'))
    } finally {
      setUpdating(false)
    }
  }

  async function handleDelete() {
    if (!accessToken || !deleteTarget) return
    setDeleting(true)
    setDeleteError(null)
    try {
      await deleteRuntimeTarget(projectId, deleteTarget.id, accessToken)
      setTargets((prev) => prev.filter((t) => t.id !== deleteTarget.id))
      setDeleteTarget(null)
    } catch (err) {
      setDeleteError(apiErrorMessage(err, 'Failed to delete runtime target.'))
    } finally {
      setDeleting(false)
    }
  }

  async function handleRetry(target: RuntimeTarget) {
    if (!accessToken) return
    setRetrying(target.id)
    try {
      const updated = await provisionRuntimeTarget(projectId, target.id, accessToken)
      setTargets((prev) => prev.map((t) => (t.id === updated.id ? updated : t)))
    } catch (err) {
      setTargets((prev) =>
        prev.map((t) =>
          t.id === target.id
            ? { ...t, status: 'ERROR', errorMessage: apiErrorMessage(err, 'Retry failed.') }
            : t,
        ),
      )
    } finally {
      setRetrying(null)
    }
  }

  const header = (
    <div className="flex items-center justify-between gap-4">
      <div>
        <h2 className="text-base font-semibold text-foreground">Runtime targets</h2>
        <p className="text-sm text-muted-foreground">
          Named Cloud Run targets — reference them in workflows with{' '}
          <code className="font-mono text-xs">runs-on: &lt;name&gt;</code>.
        </p>
      </div>
      {canMutate && activeGcpConnections.length > 0 && (
        <Button size="sm" onClick={() => setCreateOpen(true)}>
          <PlusIcon className="h-4 w-4 mr-1.5" />
          Add runtime
        </Button>
      )}
    </div>
  )

  if (loading) {
    return (
      <div className="space-y-4">
        {header}
        <div className="animate-pulse h-32 bg-muted rounded-lg" />
      </div>
    )
  }

  return (
    <div className="space-y-4">
      {header}

      {loadError && <p className="text-sm text-destructive" role="alert">{loadError}</p>}

      {!loadError && targets.length === 0 ? (
        activeGcpConnections.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            Connect an active Google Cloud service account above to add runtime targets.
          </p>
        ) : (
          <div className="bg-card rounded-lg border border-border p-8 text-center">
            <h3 className="text-base font-semibold text-foreground mb-1">No runtime targets yet</h3>
            <p className="text-sm text-muted-foreground mb-4">
              Add a runtime target to run claude-code workflow steps in your own GCP project.
            </p>
            {canMutate && (
              <Button size="sm" onClick={() => setCreateOpen(true)}>
                <PlusIcon className="h-4 w-4 mr-1.5" />
                Add runtime
              </Button>
            )}
          </div>
        )
      ) : (
        !loadError && (
          <div className="bg-card rounded-lg border border-border divide-y divide-border">
            {targets.map((target) => (
              <div key={target.id} className="px-4 py-3">
                <div className="flex items-center gap-4">
                  <div className="flex-1 min-w-0 grid grid-cols-1 sm:grid-cols-4 gap-2 sm:gap-4">
                    <div className="font-mono text-sm text-foreground truncate" title={target.name}>
                      {target.name}
                    </div>
                    <div className="text-sm text-muted-foreground">Cloud Run Job</div>
                    <div className="text-sm text-muted-foreground truncate">{target.region}</div>
                    <div className="text-sm text-muted-foreground truncate font-mono" title={target.image}>
                      {target.image}
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    {target.status === 'PROVISIONING' ? (
                      <Badge variant="secondary" className="gap-1">
                        <Loader2Icon className="h-3 w-3 animate-spin" />
                        Provisioning
                      </Badge>
                    ) : target.status === 'ACTIVE' ? (
                      <Badge variant="status-done">Active</Badge>
                    ) : (
                      <Badge variant="destructive">Error</Badge>
                    )}
                    {canMutate && (
                      <RowActionsMenu
                        onEdit={() => openEditModal(target)}
                        onDelete={() => { setDeleteTarget(target); setDeleteError(null) }}
                        extraItems={
                          target.status === 'ERROR'
                            ? [{
                                label: retrying === target.id ? 'Retrying…' : 'Retry provisioning',
                                onSelect: () => handleRetry(target),
                              }]
                            : []
                        }
                      />
                    )}
                  </div>
                </div>
                {target.errorMessage && (
                  <Alert variant="destructive" className="mt-1 py-1.5 text-xs">
                    {target.errorMessage}
                  </Alert>
                )}
                {target.warnings && target.warnings.length > 0 && (
                  <p className="mt-1 text-xs text-status-progress">
                    {target.warnings.join(' ')}
                  </p>
                )}
              </div>
            ))}
          </div>
        )
      )}

      <RuntimeTargetCreateModal
        projectId={projectId}
        connections={activeGcpConnections}
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(created) => setTargets((prev) => [...prev, created])}
      />

      {/* Edit modal */}
      <Modal
        open={editTarget !== null}
        onOpenChange={(o) => { if (!o) setEditTarget(null) }}
        title={editTarget ? `Edit ${editTarget.name}` : ''}
        description="Changing region, image, or job name re-provisions the Cloud Run Job."
      >
        {editTarget && (
          <form onSubmit={handleEdit} className="space-y-4">
            <div>
              <label htmlFor="edit-runtime-region" className="block text-sm font-medium text-foreground mb-1">Region</label>
              <input
                id="edit-runtime-region"
                type="text"
                value={editForm.region}
                onChange={(e) => setEditForm((f) => ({ ...f, region: e.target.value }))}
                required
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            <div>
              <label htmlFor="edit-runtime-image" className="block text-sm font-medium text-foreground mb-1">Image</label>
              <input
                id="edit-runtime-image"
                type="text"
                value={editForm.image}
                onChange={(e) => setEditForm((f) => ({ ...f, image: e.target.value }))}
                required
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            <div>
              <label htmlFor="edit-runtime-job-name" className="block text-sm font-medium text-foreground mb-1">Job name</label>
              <input
                id="edit-runtime-job-name"
                type="text"
                value={editForm.jobName}
                onChange={(e) => setEditForm((f) => ({ ...f, jobName: e.target.value }))}
                required
                className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-ring"
              />
            </div>
            {editError && <p className="text-sm text-destructive" role="alert">{editError}</p>}
            <div className="flex gap-3 pt-1">
              <Button type="button" variant="outline" className="flex-1" onClick={() => setEditTarget(null)}>
                Cancel
              </Button>
              <Button type="submit" className="flex-1" disabled={updating}>
                {updating ? 'Saving…' : 'Save'}
              </Button>
            </div>
          </form>
        )}
      </Modal>

      {/* Delete confirm modal */}
      <Modal
        open={deleteTarget !== null}
        onOpenChange={(o) => { if (!o) setDeleteTarget(null) }}
        title="Delete runtime target"
      >
        <p className="text-sm text-foreground">
          Delete <strong>{deleteTarget?.name}</strong>? Workflows still referencing{' '}
          <code className="font-mono text-xs">runs-on: {deleteTarget?.name}</code> will fail. This does
          not delete the Cloud Run Job in your GCP project.
        </p>
        {deleteError && <p className="mt-2 text-sm text-destructive" role="alert">{deleteError}</p>}
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleDelete} disabled={deleting}>
            {deleting ? 'Deleting…' : 'Delete'}
          </Button>
          <Button variant="outline" onClick={() => setDeleteTarget(null)}>
            Cancel
          </Button>
        </div>
      </Modal>
    </div>
  )
}
