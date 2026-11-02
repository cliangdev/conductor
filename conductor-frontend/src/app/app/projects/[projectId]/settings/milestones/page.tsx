'use client'

// Settings › Milestones — the dated goals PRDs are grouped under (docs/prd-tracking.md).

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { FlagIcon } from 'lucide-react'
import { PageHeader } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { DateTimePicker } from '@/components/ui/date-time-picker'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { Skeleton } from '@/components/ui/skeleton'
import { toastError, toastSuccess } from '@/components/ui/toast'
import { ProgressBar } from '@/components/roadmap/ProgressBar'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { createMilestone, deleteMilestone, fetchMilestones, patchMilestone } from '@/lib/delivery'
import { settingsBreadcrumbs } from '@/lib/navigation'
import { parseTargetOn } from '@/lib/roadmap'
import type { Milestone } from '@/types/workItem'

function formatTarget(targetOn: string | null | undefined): string {
  const ms = parseTargetOn(targetOn)
  return ms === null ? 'No date' : new Date(ms).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

export default function MilestonesSettingsPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  // The backend rejects milestone writes from anyone below CREATOR, so the controls are hidden for them.
  const canManage = usePermissions().can('milestone.manage')

  const [milestones, setMilestones] = useState<Milestone[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  // `editing` null with the modal open means creating.
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<Milestone | null>(null)
  const [name, setName] = useState('')
  const [targetOn, setTargetOn] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const [deleting, setDeleting] = useState<Milestone | null>(null)
  const [deleteBusy, setDeleteBusy] = useState(false)

  const load = useCallback(() => {
    if (!accessToken) return
    fetchMilestones(projectId, accessToken)
      .then((data) => {
        setMilestones(data)
        setLoadError(null)
      })
      .catch((err) => setLoadError(apiErrorMessage(err, 'Failed to load milestones.')))
  }, [projectId, accessToken])

  useEffect(load, [load])

  function openForm(milestone: Milestone | null) {
    setEditing(milestone)
    setName(milestone?.name ?? '')
    setTargetOn(milestone?.targetOn ? `${milestone.targetOn.slice(0, 10)}T00:00` : '')
    setFormError(null)
    setFormOpen(true)
  }

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken) return
    const trimmed = name.trim()
    if (!trimmed) {
      setFormError('Name is required.')
      return
    }
    const date = targetOn ? targetOn.slice(0, 10) : null
    setSaving(true)
    setFormError(null)
    try {
      if (editing) {
        await patchMilestone(projectId, editing.id, { name: trimmed, targetOn: date }, accessToken)
      } else {
        await createMilestone(projectId, { name: trimmed, targetOn: date }, accessToken)
      }
      setFormOpen(false)
      toastSuccess(editing ? 'Milestone updated' : 'Milestone created')
      load()
    } catch (err) {
      setFormError(apiErrorMessage(err, 'Failed to save the milestone.'))
    } finally {
      setSaving(false)
    }
  }

  async function toggleState(milestone: Milestone) {
    if (!accessToken) return
    try {
      await patchMilestone(projectId, milestone.id, { state: milestone.state === 'OPEN' ? 'CLOSED' : 'OPEN' }, accessToken)
      load()
    } catch (err) {
      toastError(apiErrorMessage(err, 'Failed to update the milestone.'))
    }
  }

  async function handleDelete() {
    if (!accessToken || !deleting) return
    setDeleteBusy(true)
    try {
      await deleteMilestone(projectId, deleting.id, accessToken)
      setDeleting(null)
      toastSuccess('Milestone deleted')
      load()
    } catch (err) {
      toastError(apiErrorMessage(err, 'Failed to delete the milestone.'))
    } finally {
      setDeleteBusy(false)
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Milestones"
        description="Dated goals that PRDs are grouped under. They set the Now and Next columns on the roadmap."
        actions={canManage ? <Button size="sm" onClick={() => openForm(null)}>Add milestone</Button> : undefined}
        breadcrumbs={settingsBreadcrumbs(projectId, 'settings-milestones')}
      />

      {loadError ? (
        <EmptyState icon={FlagIcon} title="Could not load milestones" description={loadError} />
      ) : milestones === null ? (
        <div className="space-y-2" aria-busy="true">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : milestones.length === 0 ? (
        <EmptyState icon={FlagIcon} title="No milestones yet" description="Add one, then pick it on a PRD to group the work." />
      ) : (
        <Card>
          <CardContent>
            {milestones.map((m) => (
              <div key={m.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 text-sm">
                <span className="min-w-0 flex-1 basis-40 truncate font-medium text-foreground">{m.name}</span>
                <span className="w-28 text-muted-foreground">{formatTarget(m.targetOn)}</span>
                <span className="w-16 text-muted-foreground">{m.state === 'OPEN' ? 'Open' : 'Closed'}</span>
                <span className="flex w-40 items-center gap-2">
                  <ProgressBar done={m.progress?.leafDone ?? 0} total={m.progress?.leafTotal ?? 0} label={`${m.name} progress`} />
                  <span className="shrink-0 text-xs text-muted-foreground">
                    {m.progress?.leafDone ?? 0}/{m.progress?.leafTotal ?? 0}
                  </span>
                </span>
                {canManage && (
                <span className="flex items-center gap-1">
                  <Button variant="ghost" size="sm" onClick={() => openForm(m)}>Edit</Button>
                  <Button variant="ghost" size="sm" onClick={() => void toggleState(m)}>
                    {m.state === 'OPEN' ? 'Close' : 'Reopen'}
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setDeleting(m)}
                    className="text-destructive hover:text-destructive hover:bg-destructive/10"
                    aria-label={`Delete milestone ${m.name}`}
                  >
                    Delete
                  </Button>
                </span>
                )}
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      <Modal open={formOpen} onOpenChange={(o) => { if (!o) setFormOpen(false) }} title={editing ? 'Edit milestone' : 'Add milestone'}>
        <form onSubmit={handleSave} className="space-y-4">
          <div>
            <Label htmlFor="milestone-name">Name</Label>
            <Input id="milestone-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="Beta launch" />
          </div>
          <div>
            <Label htmlFor="milestone-target">Target date</Label>
            <DateTimePicker id="milestone-target" label="Target date" value={targetOn} onChange={setTargetOn} clearable />
          </div>
          {formError && <p className="text-sm text-destructive" role="alert">{formError}</p>}
          <div className="flex gap-3">
            <Button type="submit" disabled={saving}>{saving ? 'Saving…' : 'Save'}</Button>
            <Button type="button" variant="outline" onClick={() => setFormOpen(false)} disabled={saving}>Cancel</Button>
          </div>
        </form>
      </Modal>

      <ConfirmModal
        open={deleting !== null}
        title="Delete milestone"
        confirmLabel="Delete"
        busyLabel="Deleting…"
        busy={deleteBusy}
        onConfirm={() => void handleDelete()}
        onCancel={() => setDeleting(null)}
      >
        <p className="text-sm text-foreground">
          Delete <strong>{deleting?.name}</strong>? PRDs grouped under it stay, but lose their milestone.
        </p>
      </ConfirmModal>
    </div>
  )
}
