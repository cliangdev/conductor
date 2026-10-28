'use client'

// The Posts-only bulk actions in the floating bar: reschedule, set destinations, retry failed
// destinations, add/remove tags and delete. Each fans out per item through the list's shared
// `run` helper (runBulkMutation), which reports "N done, M failed" and reselects just the failures.
// An action that cannot apply to the current selection is disabled and says why on hover.

import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { DateTimePicker } from '@/components/ui/date-time-picker'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { Select } from '@/components/ui/select'
import { TimeZonePicker } from '@/components/ui/time-zone-picker'
import { apiDelete, apiErrorMessage, apiGet, apiPatch, apiPost, apiPut } from '@/lib/api'
import { browserTimeZone, wallClockToInstant } from '@/lib/schedule'
import type { PublishTargetOption, SelectedPublishTarget } from '@/components/marketing/destinations/types'
import type { IssueWithReviewers } from '@/components/workitems/listTypes'
import { applyTagChange, hasFailedTarget, isEditLocked, isLivePost, parseTags, shiftSchedule, tagsDiffer } from '@/components/workitems/postBulk'

/** `failure` is the full tail of the toast: "N of M Work Items failed {failure}". */
type Run = (ids: string[], mutate: (id: string) => Promise<unknown>, failure: string) => Promise<void>

export interface PostBulkActionsProps {
  projectId: string
  token: string
  /** The selected Posts (resolved from the loaded list). */
  selected: IssueWithReviewers[]
  disabled?: boolean
  run: Run
}

type Dialog = null | 'schedule' | 'destinations' | 'tags' | 'delete'

export function PostBulkActions({ projectId, token, selected, disabled, run }: PostBulkActionsProps) {
  const [dialog, setDialog] = useState<Dialog>(null)
  const base = `/api/v2/projects/${projectId}/work-items`
  const failed = selected.filter(hasFailedTarget)
  const live = selected.filter(isLivePost)
  const deletable = selected.filter((p) => !isLivePost(p))
  // Reschedule and Destinations leave live, approved, scheduled and handed-off posts alone.
  const editable = selected.filter((p) => !isEditLocked(p))
  const locked = selected.filter(isEditLocked)
  const lockedReason = 'Every selected post is live, approved or already handed to a platform'

  const close = () => setDialog(null)

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        disabled={disabled || editable.length === 0}
        title={editable.length === 0 ? lockedReason : undefined}
        onClick={() => setDialog('schedule')}
      >
        Reschedule
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={disabled || editable.length === 0}
        title={editable.length === 0 ? lockedReason : undefined}
        onClick={() => setDialog('destinations')}
      >
        Destinations
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={disabled || failed.length === 0}
        title={failed.length === 0 ? 'None of the selected posts has a failed destination' : undefined}
        onClick={() =>
          void run(
            failed.map((p) => p.id),
            (id) => apiPost(`${base}/${id}/publish-targets/retry`, {}, token),
            'to retry the failed destinations',
          )
        }
      >
        Retry failed{failed.length > 0 ? ` (${failed.length})` : ''}
      </Button>
      <Button variant="outline" size="sm" disabled={disabled} onClick={() => setDialog('tags')}>
        Tags
      </Button>
      <Button
        variant="outline"
        size="sm"
        className="text-destructive"
        disabled={disabled || deletable.length === 0}
        title={deletable.length === 0 ? 'Live posts cannot be deleted in bulk' : undefined}
        onClick={() => setDialog('delete')}
      >
        Delete
      </Button>

      {dialog === 'schedule' && (
        <RescheduleDialog
          posts={editable}
          excluded={locked}
          onClose={close}
          onApply={(plan) => {
            close()
            const byId = new Map(editable.map((p) => [p.id, p]))
            const ids = editable.filter((p) => plan(p) !== null).map((p) => p.id)
            void run(ids, (id) => apiPatch(`${base}/${id}`, plan(byId.get(id)!)!, token), 'to reschedule')
          }}
        />
      )}
      {dialog === 'destinations' && (
        <DestinationsDialog
          projectId={projectId}
          token={token}
          count={editable.length}
          excluded={locked}
          onClose={close}
          onApply={(targets) => {
            close()
            void run(
              editable.map((p) => p.id),
              async (id) => {
                // Targets that stay selected keep their own settings (TikTok consent options, format,
                // caption, media); only newly added ones start plain. Accounts left unticked are removed.
                const current = await apiGet<SelectedPublishTarget[]>(`${base}/${id}/publish-targets`, token)
                const keyOf = (platform: string, connectionId: string | null) => `${platform}:${connectionId ?? 'manual'}`
                const existing = new Map(current.map((t) => [keyOf(t.platform, t.connectionId), t]))
                const payload = targets.map((t) => {
                  const kept = existing.get(keyOf(t.platform, t.connectionId))
                  if (!kept) return t
                  return {
                    ...t,
                    format: kept.format ?? undefined,
                    publishOptions: kept.publishOptions ?? undefined,
                    captionOverride: kept.captionOverride ?? undefined,
                    assetIds: kept.assetIds ?? undefined,
                  }
                })
                return apiPut(`${base}/${id}/publish-targets`, { targets: payload }, token)
              },
              'to update destinations',
            )
          }}
        />
      )}
      {dialog === 'tags' && (
        <TagsDialog
          posts={selected}
          onClose={close}
          onApply={(change, mode) => {
            close()
            const byId = new Map(selected.map((p) => [p.id, p]))
            const ids = selected.filter((p) => tagsDiffer(p.tags, change, mode)).map((p) => p.id)
            void run(
              ids,
              (id) => apiPatch(`${base}/${id}`, { tags: applyTagChange(byId.get(id)!.tags, change, mode) }, token),
              'to update tags',
            )
          }}
        />
      )}
      {dialog === 'delete' && (
        <ConfirmModal
          open
          title={`Delete ${deletable.length} ${deletable.length === 1 ? 'post' : 'posts'}?`}
          description="This removes them from Conductor only. A post handed to Facebook or YouTube for a later time is taken back."
          confirmLabel={`Delete ${deletable.length}`}
          cancelLabel="Keep them"
          onCancel={close}
          onConfirm={() => {
            close()
            void run(
              deletable.map((p) => p.id),
              (id) => apiDelete(`${base}/${id}`, token),
              'to delete',
            )
          }}
        >
          {live.length > 0 && (
            <p className="mb-3 rounded-md border border-border bg-surface-2 p-2 text-sm text-foreground" data-testid="delete-live-note">
              {live.length} live {live.length === 1 ? 'post is' : 'posts are'} not included and will stay:{' '}
              {live.map((p) => p.displayId ?? p.title).join(', ')}.
            </p>
          )}
          <ul className="max-h-48 space-y-1 overflow-y-auto text-sm text-foreground">
            {deletable.map((p) => (
              <li key={p.id} className="truncate">
                <span className="mr-2 font-mono text-xs text-muted-foreground">{p.displayId}</span>
                {p.title}
              </li>
            ))}
          </ul>
        </ConfirmModal>
      )}
    </>
  )
}

/** Names the posts a bulk edit leaves alone, so nobody wonders why the count is lower than the selection. */
function ExcludedNote({ posts }: { posts: IssueWithReviewers[] }) {
  if (posts.length === 0) return null
  return (
    <p className="rounded-md border border-border bg-surface-2 p-2 text-sm text-foreground" data-testid="excluded-note">
      {posts.length} {posts.length === 1 ? 'post is' : 'posts are'} live, approved or already handed to a platform
      and will not be changed: {posts.map((p) => p.displayId ?? p.title).join(', ')}.
    </p>
  )
}

function RescheduleDialog({
  posts,
  excluded,
  onClose,
  onApply,
}: {
  posts: IssueWithReviewers[]
  excluded: IssueWithReviewers[]
  onClose: () => void
  onApply: (plan: (p: IssueWithReviewers) => { scheduledFor: string; scheduleTimezone: string } | null) => void
}) {
  const [mode, setMode] = useState<'set' | 'shift'>('set')
  const [when, setWhen] = useState('')
  const [zone, setZone] = useState(browserTimeZone())
  const [days, setDays] = useState('1')
  const scheduledCount = posts.filter((p) => p.scheduledFor).length
  const n = Number.parseInt(days, 10)
  const valid = mode === 'set' ? Boolean(wallClockToInstant(when, zone)) : Number.isFinite(n) && n !== 0 && scheduledCount > 0

  return (
    <Modal
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Reschedule ${posts.length} ${posts.length === 1 ? 'post' : 'posts'}`}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button
            size="sm"
            disabled={!valid}
            onClick={() =>
              onApply((p) => {
                if (mode === 'set') {
                  const iso = wallClockToInstant(when, zone)
                  return iso ? { scheduledFor: iso, scheduleTimezone: zone } : null
                }
                const iso = shiftSchedule(p.scheduledFor, p.scheduleTimezone, n)
                return iso ? { scheduledFor: iso, scheduleTimezone: p.scheduleTimezone ?? 'UTC' } : null
              })
            }
          >
            Reschedule
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        <ExcludedNote posts={excluded} />
        <Select aria-label="Reschedule mode" value={mode} onChange={(e) => setMode(e.target.value as 'set' | 'shift')}>
          <option value="set">Set a date and time</option>
          <option value="shift">Shift by days</option>
        </Select>
        {mode === 'set' ? (
          <>
            <DateTimePicker value={when} onChange={setWhen} label="Date and time" />
            <TimeZonePicker value={zone} onChange={setZone} />
          </>
        ) : (
          <>
            <div>
              <Label htmlFor="bulk-shift-days">Days (negative moves earlier)</Label>
              <Input id="bulk-shift-days" type="number" value={days} onChange={(e) => setDays(e.target.value)} />
            </div>
            <p className="text-xs text-muted-foreground">
              {scheduledCount === 0
                ? 'None of the selected posts has a date to shift.'
                : `${scheduledCount} of ${posts.length} have a date; the rest are left alone.`}
            </p>
          </>
        )}
      </div>
    </Modal>
  )
}

function DestinationsDialog({
  projectId,
  token,
  count,
  excluded,
  onClose,
  onApply,
}: {
  projectId: string
  token: string
  count: number
  excluded: IssueWithReviewers[]
  onClose: () => void
  onApply: (targets: { platform: string; connectionId: string | null }[]) => void
}) {
  const [options, setOptions] = useState<PublishTargetOption[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const keyOf = (o: PublishTargetOption) => `${o.platform}:${o.connectionId ?? 'manual'}`

  useEffect(() => {
    let cancelled = false
    apiGet<PublishTargetOption[]>(`/api/v2/projects/${projectId}/publish-targets`, token)
      .then((o) => !cancelled && setOptions(o))
      .catch((e) => !cancelled && setError(apiErrorMessage(e, 'Could not load destinations')))
    return () => {
      cancelled = true
    }
  }, [projectId, token])

  return (
    <Modal
      open
      onOpenChange={(o) => !o && onClose()}
      title="Set destinations"
      description={`Replaces the destinations on ${count} ${count === 1 ? 'post' : 'posts'}. Accounts that stay ticked keep their settings; unticked ones are removed.`}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button
            size="sm"
            disabled={picked.size === 0}
            onClick={() =>
              onApply(
                (options ?? [])
                  .filter((o) => picked.has(keyOf(o)))
                  .map((o) => ({ platform: o.platform, connectionId: o.connectionId })),
              )
            }
          >
            Apply
          </Button>
        </div>
      }
    >
      <div className="mb-3">
        <ExcludedNote posts={excluded} />
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      {!options && !error && <p className="text-sm text-muted-foreground">Loading…</p>}
      <div className="space-y-2">
        {(options ?? []).map((o) => (
          <Checkbox
            key={keyOf(o)}
            checked={picked.has(keyOf(o))}
            onCheckedChange={(c) =>
              setPicked((prev) => {
                const next = new Set(prev)
                if (c) next.add(keyOf(o))
                else next.delete(keyOf(o))
                return next
              })
            }
            label={`${o.label} (${o.platform}${o.lane === 'MANUAL' ? ', manual' : ''})`}
          />
        ))}
      </div>
    </Modal>
  )
}

function TagsDialog({
  posts,
  onClose,
  onApply,
}: {
  posts: IssueWithReviewers[]
  onClose: () => void
  onApply: (tags: string[], mode: 'add' | 'remove') => void
}) {
  const [mode, setMode] = useState<'add' | 'remove'>('add')
  const [text, setText] = useState('')
  const tags = parseTags(text)
  const affected = posts.filter((p) => tagsDiffer(p.tags, tags, mode)).length
  return (
    <Modal
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Tag ${posts.length} ${posts.length === 1 ? 'post' : 'posts'}`}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button size="sm" disabled={tags.length === 0 || affected === 0} onClick={() => onApply(tags, mode)}>
            {mode === 'add' ? 'Add tags' : 'Remove tags'}
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        <Select aria-label="Tag action" value={mode} onChange={(e) => setMode(e.target.value as 'add' | 'remove')}>
          <option value="add">Add tags</option>
          <option value="remove">Remove tags</option>
        </Select>
        <div>
          <Label htmlFor="bulk-tags">Tags (comma separated)</Label>
          <Input id="bulk-tags" value={text} onChange={(e) => setText(e.target.value)} placeholder="autumn, launch" />
        </div>
        {tags.length > 0 && (
          <p className="text-xs text-muted-foreground">
            {affected} of {posts.length} {posts.length === 1 ? 'post' : 'posts'} will change.
          </p>
        )}
      </div>
    </Modal>
  )
}
