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
import {
  applyTagChange,
  hasFailedTarget,
  isEditLocked,
  isLivePost,
  isLiveUnknown,
  isRescheduleLocked,
  parseTags,
  shiftSchedule,
  tagsDiffer,
  todayInZone,
} from '@/components/workitems/postBulk'

/** `failure` is the full tail of the toast: "N of M Work Items failed {failure}". */
type Run = (ids: string[], mutate: (id: string) => Promise<unknown>, failure: string, note?: string) => Promise<void>

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
  // Fail closed: a post we cannot confirm is not live (not enriched, or enrichment failed) is never deleted.
  const unknownLive = selected.filter((p) => !isLivePost(p) && isLiveUnknown(p))
  const deletable = selected.filter((p) => !isLivePost(p) && !isLiveUnknown(p))
  // Reschedule leaves only live posts alone (a schedule edit is allowed everywhere else, as on a single
  // post). Destinations is stricter: replacing them revokes a hand-off, so it also skips approved,
  // scheduled and handed-off posts.
  const reschedulable = selected.filter((p) => !isRescheduleLocked(p))
  const scheduleLocked = selected.filter(isRescheduleLocked)
  const editable = selected.filter((p) => !isEditLocked(p))
  const locked = selected.filter(isEditLocked)
  const scheduleLockedReason = 'Every selected post is already live'
  const destinationsLockedReason = 'Every selected post is live, approved, already handed to a platform, or could not be confirmed as not live'
  const deleteLockedReason =
    unknownLive.length > 0
      ? `Could not confirm these are not live: ${unknownLive.map((p) => p.displayId ?? p.title).join(', ')}`
      : 'Every selected post is live; live posts cannot be deleted in bulk'
  const failedReason = 'None of the selected posts has a failed destination'
  // Shown as text beside the bar (a title only helps a mouse): why each disabled action is disabled.
  const disabledReasons =
    selected.length === 0
      ? []
      : [
          reschedulable.length === 0 ? `Reschedule: ${scheduleLockedReason.toLowerCase()}` : null,
          editable.length === 0 ? `Destinations: ${destinationsLockedReason.toLowerCase()}` : null,
          failed.length === 0 ? `Retry failed: ${failedReason.toLowerCase()}` : null,
          deletable.length === 0 ? `Delete: ${deleteLockedReason}` : null,
        ].filter((r): r is string => r !== null)

  const close = () => setDialog(null)

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        disabled={disabled || reschedulable.length === 0}
        title={reschedulable.length === 0 ? scheduleLockedReason : undefined}
        onClick={() => setDialog('schedule')}
      >
        Reschedule
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={disabled || editable.length === 0}
        title={editable.length === 0 ? destinationsLockedReason : undefined}
        onClick={() => setDialog('destinations')}
      >
        Destinations
      </Button>
      <Button
        variant="outline"
        size="sm"
        disabled={disabled || failed.length === 0}
        title={failed.length === 0 ? failedReason : undefined}
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
        title={deletable.length === 0 ? deleteLockedReason : undefined}
        onClick={() => setDialog('delete')}
      >
        Delete
      </Button>
      {disabledReasons.length > 0 && (
        <p className="w-full text-xs text-muted-foreground" role="status" data-testid="disabled-reasons">
          {disabledReasons.join(' · ')}
        </p>
      )}

      {dialog === 'schedule' && (
        <RescheduleDialog
          posts={reschedulable}
          excluded={scheduleLocked}
          onClose={close}
          onApply={(plan) => {
            close()
            const byId = new Map(reschedulable.map((p) => [p.id, p]))
            const ids = reschedulable.filter((p) => plan(p) !== null).map((p) => p.id)
            // Posts the plan cannot place (no date to shift, or a time that has passed) are counted and said so.
            const skipped = reschedulable.length - ids.length
            const note =
              skipped > 0
                ? `${skipped} skipped: ${skipped === 1 ? 'it has' : 'they have'} no date to shift, or the new time has already passed`
                : undefined
            void run(ids, (id) => apiPatch(`${base}/${id}`, plan(byId.get(id)!)!, token), 'to reschedule', note)
          }}
        />
      )}
      {dialog === 'destinations' && (
        <DestinationsDialog
          projectId={projectId}
          token={token}
          posts={editable}
          excluded={locked}
          onClose={close}
          onApply={(targets, mode) => {
            close()
            void run(
              editable.map((p) => p.id),
              async (id) => {
                // Read fresh per post. Any account that stays keeps its own settings (TikTok consent options,
                // format, caption, media); only newly added ones start plain.
                const current = await apiGet<SelectedPublishTarget[]>(`${base}/${id}/publish-targets`, token)
                const keyOf = (platform: string, connectionId: string | null) => `${platform}:${connectionId ?? 'manual'}`
                const existing = new Map(current.map((t) => [keyOf(t.platform, t.connectionId), t]))
                const keep = (t: SelectedPublishTarget) => ({
                  platform: t.platform,
                  connectionId: t.connectionId,
                  format: t.format ?? undefined,
                  publishOptions: t.publishOptions ?? undefined,
                  captionOverride: t.captionOverride ?? undefined,
                  assetIds: t.assetIds ?? undefined,
                })
                const picked = targets.map((t) => {
                  const kept = existing.get(keyOf(t.platform, t.connectionId))
                  return kept ? keep(kept) : t
                })
                // Add: everything already on the post stays, plus the ticked accounts it lacks. Replace: only
                // the ticked accounts remain.
                const payload =
                  mode === 'add'
                    ? [...current.map(keep), ...picked.filter((t) => !existing.has(keyOf(t.platform, t.connectionId)))]
                    : picked
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
          {unknownLive.length > 0 && (
            <p className="mb-3 rounded-md border border-border bg-surface-2 p-2 text-sm text-foreground" data-testid="delete-unknown-note">
              Could not confirm {unknownLive.length === 1 ? 'this post is' : 'these posts are'} not live, so{' '}
              {unknownLive.length === 1 ? 'it is' : 'they are'} not included and will stay:{' '}
              {unknownLive.map((p) => p.displayId ?? p.title).join(', ')}.
            </p>
          )}
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
function ExcludedNote({ posts, reason }: { posts: IssueWithReviewers[]; reason: string }) {
  if (posts.length === 0) return null
  return (
    <p className="rounded-md border border-border bg-surface-2 p-2 text-sm text-foreground" data-testid="excluded-note">
      {posts.length} {posts.length === 1 ? 'post is' : 'posts are'} {reason} and will not be changed:{' '}
      {posts.map((p) => p.displayId ?? p.title).join(', ')}.
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
  // Read once when the dialog opens (render must stay pure) and checked again on Apply.
  const [openedAt] = useState(() => Date.now())
  const now = openedAt
  // A time in the past is refused here with a clear message (the server would only say it was too soon).
  const setInstant = mode === 'set' ? wallClockToInstant(when, zone) : null
  const setInPast = setInstant != null && new Date(setInstant).getTime() <= now
  const shiftedInPast =
    mode === 'shift' && Number.isFinite(n) && n !== 0
      ? posts.filter((p) => {
          const next = shiftSchedule(p.scheduledFor, p.scheduleTimezone, n)
          return next != null && new Date(next).getTime() <= now
        }).length
      : 0
  const valid =
    mode === 'set'
      ? setInstant != null && !setInPast
      : Number.isFinite(n) && n !== 0 && scheduledCount > 0 && shiftedInPast === 0
  // The earliest offered day is today in the CHOSEN zone, not the UTC date (they differ for hours every day).
  const today = todayInZone(openedAt, zone)

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
                  // Checked again at click time: the dialog may have sat open past the chosen minute.
                  return iso && new Date(iso).getTime() > Date.now() ? { scheduledFor: iso, scheduleTimezone: zone } : null
                }
                const iso = shiftSchedule(p.scheduledFor, p.scheduleTimezone, n)
                return iso && new Date(iso).getTime() > Date.now()
                  ? { scheduledFor: iso, scheduleTimezone: p.scheduleTimezone ?? 'UTC' }
                  : null
              })
            }
          >
            Reschedule
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        <ExcludedNote posts={excluded} reason="already live" />
        <Select aria-label="Reschedule mode" value={mode} onChange={(e) => setMode(e.target.value as 'set' | 'shift')}>
          <option value="set">Set a date and time</option>
          <option value="shift">Shift by days</option>
        </Select>
        {mode === 'set' ? (
          <>
            <DateTimePicker value={when} onChange={setWhen} label="Date and time" min={today} />
            {setInPast && (
              <p className="text-sm text-destructive" data-testid="past-time">
                That time is in the past. Pick a time in the future.
              </p>
            )}
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
            {shiftedInPast > 0 && (
              <p className="text-sm text-destructive" data-testid="past-time">
                {shiftedInPast} {shiftedInPast === 1 ? 'post would' : 'posts would'} move into the past. Choose fewer days back.
              </p>
            )}
          </>
        )}
      </div>
    </Modal>
  )
}

type DestinationMode = 'add' | 'replace'

function DestinationsDialog({
  projectId,
  token,
  posts,
  excluded,
  onClose,
  onApply,
}: {
  projectId: string
  token: string
  posts: IssueWithReviewers[]
  excluded: IssueWithReviewers[]
  onClose: () => void
  onApply: (targets: { platform: string; connectionId: string | null }[], mode: DestinationMode) => void
}) {
  const [options, setOptions] = useState<PublishTargetOption[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [mode, setMode] = useState<DestinationMode>('add')
  const [picked, setPicked] = useState<Set<string>>(new Set())
  // Accounts every selected post already has, from their current destinations.
  const [common, setCommon] = useState<Set<string>>(new Set())
  const keyOf = (o: { platform: string; connectionId: string | null }) => `${o.platform}:${o.connectionId ?? 'manual'}`

  useEffect(() => {
    let cancelled = false
    const base = `/api/v2/projects/${projectId}/work-items`
    Promise.all([
      apiGet<PublishTargetOption[]>(`/api/v2/projects/${projectId}/publish-targets`, token),
      Promise.all(posts.map((p) => apiGet<SelectedPublishTarget[]>(`${base}/${p.id}/publish-targets`, token))),
    ])
      .then(([opts, perPost]) => {
        if (cancelled) return
        setOptions(opts)
        const [first, ...rest] = perPost.map((targets) => new Set(targets.map(keyOf)))
        setCommon(new Set([...(first ?? [])].filter((k) => rest.every((r) => r.has(k)))))
      })
      .catch((e) => !cancelled && setError(apiErrorMessage(e, 'Could not load destinations')))
    return () => {
      cancelled = true
    }
    // posts is a fresh array each render; the ids are the identity
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [projectId, token, posts.map((p) => p.id).join(',')])

  function chooseMode(next: DestinationMode) {
    setMode(next)
    // Replace starts from what the posts have now, so ticking one more keeps the rest; Add starts empty.
    setPicked(next === 'replace' ? new Set(common) : new Set())
  }

  return (
    <Modal
      open
      onOpenChange={(o) => !o && onClose()}
      title="Set destinations"
      description={
        mode === 'add'
          ? `Adds the ticked accounts to ${posts.length} ${posts.length === 1 ? 'post' : 'posts'}; what they already have stays, with its settings.`
          : `Replaces the destinations on ${posts.length} ${posts.length === 1 ? 'post' : 'posts'} with the ticked accounts. Unticked accounts are removed; ticked ones keep their settings.`
      }
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
                mode,
              )
            }
          >
            {mode === 'add' ? 'Add' : 'Replace'}
          </Button>
        </div>
      }
    >
      <div className="mb-3 space-y-3">
        <ExcludedNote posts={excluded} reason="live, approved, already handed to a platform, or not confirmed as not live" />
        <Select aria-label="Destinations mode" value={mode} onChange={(e) => chooseMode(e.target.value as DestinationMode)}>
          <option value="add">Add to current destinations</option>
          <option value="replace">Replace current destinations</option>
        </Select>
      </div>
      {error && <p className="text-sm text-destructive">{error}</p>}
      {!options && !error && <p className="text-sm text-muted-foreground">Loading…</p>}
      <div className="space-y-2">
        {(options ?? []).map((o) => {
          const onAll = mode === 'add' && common.has(keyOf(o))
          return (
            <Checkbox
              key={keyOf(o)}
              checked={onAll || picked.has(keyOf(o))}
              disabled={onAll}
              onCheckedChange={(c) =>
                setPicked((prev) => {
                  const next = new Set(prev)
                  if (c) next.add(keyOf(o))
                  else next.delete(keyOf(o))
                  return next
                })
              }
              label={`${o.label} (${o.platform}${o.lane === 'MANUAL' ? ', manual' : ''})`}
              description={onAll ? 'Already on every selected post' : undefined}
            />
          )
        })}
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
