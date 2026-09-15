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

import { useCallback, useState } from 'react'
import { CalendarClock, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { DateTimePicker } from '@/components/ui/date-time-picker'
import { TimeZonePicker } from '@/components/ui/time-zone-picker'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiPatch } from '@/lib/api'
import { browserTimeZone, instantToWallClock, wallClockToInstant } from '@/lib/schedule'

/** Re-exported for the tests that grew up here; the one copy lives in lib/schedule. */
export { wallClockToInstant, instantToWallClock } from '@/lib/schedule'

/** What the panel shows when it is not being edited: the date on its own line, the time and zone below. */
function describeDate(iso: string, timeZone: string): string {
  try {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone }).format(new Date(iso))
  } catch {
    return iso
  }
}

function describeTime(iso: string, timeZone: string): string {
  try {
    return (
      new Intl.DateTimeFormat(undefined, { timeStyle: 'short', timeZone }).format(new Date(iso)) +
      ` ${timeZone}`
    )
  } catch {
    return timeZone
  }
}

export interface WorkItemScheduleFieldProps {
  projectId: string
  issueId: string
  token: string
  scheduledFor?: string | null
  scheduleTimezone?: string | null
  /**
   * The item goes out as soon as it is approved: no date is needed, and the status that puts it on a
   * clock stamps one. Shown in place of the date while there is none; the editor can turn it on or off.
   */
  publishOnApproval?: boolean
  /** False for a reader — a REVIEWER sees the schedule but cannot move it. */
  canEdit: boolean
  onChanged: (scheduledFor: string | null, scheduleTimezone: string | null, publishOnApproval?: boolean) => void
}

export function WorkItemScheduleField({
  projectId,
  issueId,
  token,
  scheduledFor,
  scheduleTimezone,
  publishOnApproval = false,
  canEdit,
  onChanged,
}: WorkItemScheduleFieldProps) {
  const zone = scheduleTimezone || browserTimeZone()
  const [editing, setEditing] = useState(false)
  const [saving, setSaving] = useState(false)
  const [local, setLocal] = useState('')
  const [tz, setTz] = useState(zone)
  const [onApproval, setOnApproval] = useState(publishOnApproval)
  const hasSchedule = Boolean(scheduledFor) || publishOnApproval

  const open = useCallback(() => {
    setTz(zone)
    setLocal(scheduledFor ? instantToWallClock(scheduledFor, zone) : '')
    setOnApproval(publishOnApproval)
    setEditing(true)
  }, [scheduledFor, zone, publishOnApproval])

  const save = useCallback(
    async (nextIso: string | null, nextTz: string | null, nextOnApproval: boolean) => {
      setSaving(true)
      try {
        // The flag travels only when it changes, so an item that never had it keeps a body of exactly
        // the two schedule fields.
        const flag = nextOnApproval !== publishOnApproval ? { publishOnApproval: nextOnApproval } : {}
        await apiPatch(
          `/api/v2/projects/${projectId}/work-items/${issueId}`,
          nextOnApproval
            ? { scheduleTimezone: nextTz, ...flag }
            : { scheduledFor: nextIso, scheduleTimezone: nextTz, ...flag },
          token
        )
        onChanged(nextIso, nextTz, nextOnApproval)
        setEditing(false)
      } catch (err) {
        // Never swallow: the stored schedule is unchanged and the reason is said out loud. A schedule
        // can still be refused — a time too soon for a destination, or a post that has already gone
        // out — so the server's own words matter more here than a house message would.
        toastError(apiErrorMessage(err, 'Could not update the schedule'))
      } finally {
        setSaving(false)
      }
    },
    [projectId, issueId, token, onChanged, publishOnApproval]
  )

  if (!editing) {
    return (
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-start gap-2">
          <CalendarClock className="mt-0.5 h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          {scheduledFor ? (
            // Two lines, neither truncated: a schedule is something a person has to be able to read
            // in full without opening the editor, not just recognise the shape of.
            <span className="text-sm text-foreground">
              <span className="block">{describeDate(scheduledFor, zone)}</span>
              <span className="block text-muted-foreground">{describeTime(scheduledFor, zone)}</span>
            </span>
          ) : (
            <span className="text-sm text-muted-foreground">
              {publishOnApproval ? 'As soon as approved' : 'Not scheduled'}
            </span>
          )}
        </div>
        {canEdit && (
          <button
            type="button"
            onClick={open}
            className="shrink-0 text-xs text-primary hover:underline"
          >
            {hasSchedule ? 'Change' : 'Set'}
          </button>
        )}
      </div>
    )
  }

  return (
    <div className="space-y-2">
      <Checkbox
        checked={onApproval}
        disabled={saving}
        onCheckedChange={setOnApproval}
        label="As soon as approved"
      />
      {!onApproval && (
        <DateTimePicker
          id={`sched-${issueId}`}
          label="Scheduled date and time"
          value={local}
          onChange={setLocal}
          defaultOpen
          align="end"
        />
      )}
      <label htmlFor={`tz-${issueId}`} className="sr-only">
        Schedule timezone
      </label>
      <TimeZonePicker id={`tz-${issueId}`} value={tz} onChange={setTz} disabled={saving} />
      <div className="flex items-center justify-between gap-2">
        {hasSchedule ? (
          <button
            type="button"
            disabled={saving}
            onClick={() => void save(null, null, false)}
            className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
          >
            <X className="h-3 w-3" aria-hidden="true" />
            Clear
          </button>
        ) : (
          <span />
        )}
        <div className="flex flex-col items-end gap-1">
          {!onApproval && !local && !saving && (
            <span className="text-sm text-muted-foreground">Pick a date and time first.</span>
          )}
          <div className="flex gap-2">
            <Button variant="ghost" size="sm" disabled={saving} onClick={() => setEditing(false)}>
              Cancel
            </Button>
            <Button
              size="sm"
              disabled={saving || (!onApproval && !local)}
              onClick={() => {
                if (onApproval) {
                  void save(null, tz, true)
                  return
                }
                const iso = wallClockToInstant(local, tz)
                if (iso) void save(iso, tz, false)
              }}
            >
              {saving ? 'Saving…' : 'Save'}
            </Button>
          </div>
        </div>
      </div>
    </div>
  )
}
