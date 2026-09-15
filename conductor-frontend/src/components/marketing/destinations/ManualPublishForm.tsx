'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { DateTimePicker } from '@/components/ui/date-time-picker'
import { Input } from '@/components/ui/input'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage } from '@/lib/api'
import type { SelectedPublishTarget } from './types'

/**
 * Records what a human already did: the link to the post they published by hand, and when.
 *
 * The link is required and the reason is not pedantry — there is no platform to ask, so it is the only
 * record this destination ever went out, and the thing the calendar, the Asset library and any later
 * reader all read. The time defaults to now but is editable, because the common case for filling this
 * in is a few hours after the fact and a wrong timestamp on a published post is quietly misleading.
 */
export function ManualPublishForm({
  target,
  onCancel,
  onComplete,
}: {
  target: SelectedPublishTarget
  onCancel: () => void
  onComplete: (permalink: string, publishedAt: string | null) => Promise<void>
}) {
  const [permalink, setPermalink] = useState('')
  const [publishedAt, setPublishedAt] = useState(() => localDateTimeValue(new Date()))
  const [saving, setSaving] = useState(false)
  const linkId = `manual-link-${target.id}`
  const timeId = `manual-time-${target.id}`

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!permalink.trim() || saving) return
    setSaving(true)
    try {
      await onComplete(permalink.trim(), publishedAt ? new Date(publishedAt).toISOString() : null)
    } catch (err) {
      // Never swallow: the row stays exactly as it was and the reason is said out loud.
      toastError(apiErrorMessage(err, 'Could not record this as published'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <form onSubmit={submit} className="ml-6 space-y-2.5 border-t border-border pt-3">
      <div className="space-y-1">
        <label htmlFor={linkId} className="block text-sm font-medium text-foreground">
          Link to the published post
        </label>
        <Input
          id={linkId}
          type="url"
          required
          autoFocus
          value={permalink}
          onChange={(e) => setPermalink(e.target.value)}
          placeholder="https://…"
        />
      </div>
      <div className="space-y-1">
        <span className="block text-sm font-medium text-foreground">When it went out</span>
        <DateTimePicker id={timeId} label="When it went out" value={publishedAt} onChange={setPublishedAt} />
      </div>
      <div className="flex items-center justify-end gap-2">
        {!permalink.trim() && !saving && (
          <span className="text-sm text-muted-foreground">Paste the link first.</span>
        )}
        <Button type="button" variant="ghost" size="sm" onClick={onCancel} disabled={saving}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={saving || !permalink.trim()}>
          {saving ? 'Recording…' : 'Record as published'}
        </Button>
      </div>
    </form>
  )
}

/**
 * `new Date()` as the value a `datetime-local` input accepts: local wall-clock, no zone, no seconds.
 * `toISOString` would be wrong here — it is UTC, and the input would show a time the user did not mean.
 */
export function localDateTimeValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
