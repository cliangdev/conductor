'use client'

// When a Work Item is due — the editor for the generic `scheduledFor` / `scheduleTimezone` pair.
//
// It exists because without it the fields were readable and unwritable. The calendar placed items on
// their scheduled day, the tray listed the ones with no date, and nothing anywhere in the app could set
// one. For a publishing Workflow that made the whole pipeline unreachable from a browser: the approval
// gate refuses a Post with no fire time, and the refusal named two JSON field names a person had no way
// to reach. Anyone without the MCP server was simply stuck.
//
// Deliberately domain-free, like the calendar it feeds: `scheduledFor` is a plain Work Item field and any
// Workflow may put an item on a clock, so this renders for all of them and says nothing about publishing.
//
// The timezone is stored beside the instant rather than derived from it, so a schedule reads back as the
// wall-clock time its author meant even across a DST boundary. That is why the input is a bare
// `datetime-local` (wall clock, no zone) paired with an explicit zone, and why the conversion below goes
// through the zone rather than through the viewer's own.

import { useCallback, useMemo, useState } from 'react'
import { CalendarClock, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiPatch } from '@/lib/api'

/** The viewer's own zone, the only sensible default for "when should this go out?". */
function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

/**
 * Every IANA zone the JS runtime knows, so the list is the runtime's rather than a hardcoded shortlist
 * that would omit somebody's. Falls back to a handful plus whatever is already in use, so a zone the
 * item was scheduled in never disappears from the control that edits it.
 */
function timeZones(current: string): string[] {
  let all: string[] = []
  try {
    all = (Intl as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? []
  } catch {
    all = []
  }
  if (all.length === 0) {
    all = ['UTC', 'America/Los_Angeles', 'America/New_York', 'Europe/London', 'Europe/Berlin', 'Asia/Tokyo']
  }
  return all.includes(current) ? all : [current, ...all]
}

/**
 * How far `timeZone` is from UTC at `ts`, in milliseconds. Read out of Intl rather than from a table:
 * it is the only source that knows this zone's rules on this date, DST included.
 */
function offsetAt(ts: number, timeZone: string): number {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone,
      hour12: false,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
      .formatToParts(new Date(ts))
      .map((p) => [p.type, p.value])
  ) as Record<string, string>
  const asUtc = Date.UTC(
    Number(parts['year']),
    Number(parts['month']) - 1,
    Number(parts['day']),
    Number(parts['hour']) % 24,
    Number(parts['minute']),
    Number(parts['second'])
  )
  return asUtc - ts
}

/**
 * A wall-clock `YYYY-MM-DDTHH:mm` read in `timeZone`, as an ISO instant.
 *
 * Applied twice on purpose. The first pass has to guess an offset from a timestamp that is itself still
 * wrong, so near a DST change the guess can be an hour out; correcting once more with the offset at the
 * corrected instant settles it. Converging rather than assuming is what keeps "9am on the day the clocks
 * go forward" meaning 9am.
 */
export function wallClockToInstant(local: string, timeZone: string): string | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(local)
  if (!m) return null
  const [, y, mo, d, h, mi] = m.map(Number) as unknown as number[]
  const naive = Date.UTC(y!, mo! - 1, d!, h!, mi!)
  let ts = naive
  for (let i = 0; i < 2; i++) ts = naive - offsetAt(ts, timeZone)
  const out = new Date(ts)
  return Number.isNaN(out.getTime()) ? null : out.toISOString()
}

/** The inverse: an instant, as the wall clock a `datetime-local` input shows, read in `timeZone`. */
export function instantToWallClock(iso: string, timeZone: string): string {
  const ts = new Date(iso).getTime()
  if (Number.isNaN(ts)) return ''
  const shifted = new Date(ts + offsetAt(ts, timeZone))
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}` +
    `T${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}`
  )
}

/** What the panel shows when it is not being edited. */
function describe(iso: string, timeZone: string): string {
  try {
    return new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone,
    }).format(new Date(iso)) + ` (${timeZone})`
  } catch {
    return iso
  }
}

export interface WorkItemScheduleFieldProps {
  projectId: string
  issueId: string
  token: string
  scheduledFor?: string | null
  scheduleTimezone?: string | null
  /** False for a reader — a REVIEWER sees the schedule but cannot move it. */
  canEdit: boolean
  onChanged: (scheduledFor: string | null, scheduleTimezone: string | null) => void
}

export function WorkItemScheduleField({
  projectId,
  issueId,
  token,
  scheduledFor,
  scheduleTimezone,
  canEdit,
  onChanged,
}: WorkItemScheduleFieldProps) {
  const zone = scheduleTimezone || browserTimeZone()
  const [editing, setEditing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [local, setLocal] = useState('')
  const [tz, setTz] = useState(zone)
  const zones = useMemo(() => timeZones(tz), [tz])

  const open = useCallback(() => {
    setTz(zone)
    setLocal(scheduledFor ? instantToWallClock(scheduledFor, zone) : '')
    setEditing(true)
  }, [scheduledFor, zone])

  const save = useCallback(
    async (nextIso: string | null, nextTz: string | null) => {
      setSaving(true)
      try {
        await apiPatch(
          `/api/v2/projects/${projectId}/work-items/${issueId}`,
          { scheduledFor: nextIso, scheduleTimezone: nextTz },
          token
        )
        onChanged(nextIso, nextTz)
        setEditing(false)
      } catch (err) {
        // Never swallow: the stored schedule is unchanged and the reason is said out loud. Editing a
        // schedule can also be refused outright — it reverts an approved item's bundle — so the server's
        // own words matter more here than a house message would.
        toastError(apiErrorMessage(err, 'Could not update the schedule'))
      } finally {
        setSaving(false)
      }
    },
    [projectId, issueId, token, onChanged]
  )

  if (!editing) {
    return (
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <CalendarClock className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <span
            // The panel is narrow enough to truncate a full date-time-plus-zone, so the untruncated
            // value has to stay reachable without opening the editor.
            title={scheduledFor ? describe(scheduledFor, zone) : undefined}
            className={scheduledFor ? 'truncate text-sm text-foreground' : 'text-sm text-muted-foreground'}
          >
            {scheduledFor ? describe(scheduledFor, zone) : 'Not scheduled'}
          </span>
        </div>
        {canEdit && (
          <button
            type="button"
            onClick={open}
            className="shrink-0 text-xs text-primary hover:underline"
          >
            {scheduledFor ? 'Change' : 'Set'}
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="space-y-2">
      <label htmlFor={`sched-${issueId}`} className="sr-only">
        Scheduled date and time
      </label>
      <input
        id={`sched-${issueId}`}
        type="datetime-local"
        value={local}
        autoFocus
        onChange={(e) => setLocal(e.target.value)}
        className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
      />
      <label htmlFor={`tz-${issueId}`} className="sr-only">
        Schedule timezone
      </label>
      <select
        id={`tz-${issueId}`}
        value={tz}
        onChange={(e) => setTz(e.target.value)}
        className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
      >
        {zones.map((z) => (
          <option key={z} value={z}>
            {z}
          </option>
        ))}
      </select>
      <div className="flex items-center justify-between gap-2">
        {scheduledFor ? (
          <button
            type="button"
            disabled={saving}
            onClick={() => void save(null, null)}
            className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          >
            <X className="h-3 w-3" aria-hidden="true" />
            Clear
          </button>
        ) : (
          <span />
        )}
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" disabled={saving} onClick={() => setEditing(false)}>
            Cancel
          </Button>
          <Button
            size="sm"
            disabled={saving || !local}
            onClick={() => {
              const iso = wallClockToInstant(local, tz)
              if (iso) void save(iso, tz)
            }}
          >
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </div>
    </div>
  )
}
