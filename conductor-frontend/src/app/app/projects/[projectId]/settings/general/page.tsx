'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { useProject } from '@/contexts/ProjectContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { apiDelete, apiPatch, apiErrorMessage } from '@/lib/api'
import { PageHeader } from '@/components/layout/PageHeader'
import type { Project } from '@/types'

export default function GeneralSettingsPage() {
  const params = useParams()
  const projectId = params.projectId as string
  const router = useRouter()
  const { accessToken, user } = useAuth()
  const { activeProject, updateProject, removeProject } = useProject()
  const { showToast } = useToast()

  const { can } = usePermissions()

  const [name, setName] = useState(activeProject?.name ?? '')
  const [saving, setSaving] = useState(false)

  const [deleteOpen, setDeleteOpen] = useState(false)
  const [leaveOpen, setLeaveOpen] = useState(false)
  const [dangerError, setDangerError] = useState<string | null>(null)
  const [dangerSubmitting, setDangerSubmitting] = useState(false)

  useEffect(() => {
    if (activeProject?.id === projectId) setName(activeProject.name)
  }, [activeProject?.id, activeProject?.name, projectId])

  const isAdmin = can('workspace.manage')
  const dirty = name.trim() !== '' && name.trim() !== (activeProject?.name ?? '')

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken || !dirty || !isAdmin) return
    setSaving(true)
    try {
      const updated = await apiPatch<Project>(
        `/api/v1/projects/${projectId}`,
        { name: name.trim() },
        accessToken,
      )
      if (updated) updateProject(updated)
      else if (activeProject) updateProject({ ...activeProject, name: name.trim() })
      showToast('Workspace renamed')
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to rename workspace.'), 'error')
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    if (!accessToken) return
    setDangerSubmitting(true)
    setDangerError(null)
    try {
      await apiDelete(`/api/v1/projects/${projectId}`, accessToken)
      removeProject(projectId)
      router.push('/app/projects')
    } catch (err) {
      setDangerError(apiErrorMessage(err, 'Failed to delete workspace.'))
    } finally {
      setDangerSubmitting(false)
    }
  }

  async function handleLeave() {
    if (!accessToken || !user) return
    setDangerSubmitting(true)
    setDangerError(null)
    try {
      await apiDelete(`/api/v1/projects/${projectId}/members/${user.id}`, accessToken)
      removeProject(projectId)
      router.push('/app/projects')
    } catch (err) {
      setDangerError(apiErrorMessage(err, 'Failed to leave workspace.'))
    } finally {
      setDangerSubmitting(false)
    }
  }

  return (
    <div className="space-y-10">
      <div>
        <PageHeader title="General" />

        <form onSubmit={handleSave} className="space-y-4">
          <div>
            <Label htmlFor="workspace-name">Workspace name</Label>
            <Input
              id="workspace-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={!isAdmin}
              className="max-w-sm"
            />
            {!isAdmin && (
              <p className="mt-1 text-xs text-muted-foreground">Only admins can rename this workspace.</p>
            )}
          </div>
          {isAdmin && (
            <Button type="submit" size="sm" disabled={!dirty || saving}>
              {saving ? 'Saving…' : 'Save'}
            </Button>
          )}
        </form>
      </div>

      <div>
        <h2 className="text-sm font-semibold text-destructive mb-3">Danger zone</h2>
        <div className="rounded-lg border border-destructive/30 divide-y divide-destructive/20">
          <div className="flex items-center justify-between p-4">
            <div>
              <p className="text-sm font-medium text-foreground">Leave workspace</p>
              <p className="text-xs text-muted-foreground">Remove yourself from this workspace.</p>
            </div>
            <Button variant="outline" size="sm" onClick={() => { setDangerError(null); setLeaveOpen(true) }}>
              Leave
            </Button>
          </div>
          {isAdmin && (
            <div className="flex items-center justify-between p-4">
              <div>
                <p className="text-sm font-medium text-foreground">Delete workspace</p>
                <p className="text-xs text-muted-foreground">Permanently delete this workspace and its data.</p>
              </div>
              <Button variant="destructive" size="sm" onClick={() => { setDangerError(null); setDeleteOpen(true) }}>
                Delete
              </Button>
            </div>
          )}
        </div>
      </div>

      <Modal
        open={leaveOpen}
        onOpenChange={(o) => { if (!o) setLeaveOpen(false) }}
        title="Leave workspace"
      >
        <p className="text-sm text-foreground">
          Leave <strong>{activeProject?.name ?? 'this workspace'}</strong>? You will lose access until invited again.
        </p>
        {dangerError && <p className="mt-2 text-sm text-destructive" role="alert">{dangerError}</p>}
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleLeave} disabled={dangerSubmitting}>
            {dangerSubmitting ? 'Leaving…' : 'Leave'}
          </Button>
          <Button variant="outline" onClick={() => setLeaveOpen(false)} disabled={dangerSubmitting}>
            Cancel
          </Button>
        </div>
      </Modal>

      <Modal
        open={deleteOpen}
        onOpenChange={(o) => { if (!o) setDeleteOpen(false) }}
        title="Delete workspace"
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{activeProject?.name ?? 'this workspace'}</strong>? This cannot be undone.
        </p>
        {dangerError && <p className="mt-2 text-sm text-destructive" role="alert">{dangerError}</p>}
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleDelete} disabled={dangerSubmitting}>
            {dangerSubmitting ? 'Deleting…' : 'Delete workspace'}
          </Button>
          <Button variant="outline" onClick={() => setDeleteOpen(false)} disabled={dangerSubmitting}>
            Cancel
          </Button>
        </div>
      </Modal>
    </div>
  )
}
