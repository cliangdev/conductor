'use client'

// Making a Post the way a person thinks about one: what it says, what it shows, where it goes, when.
//
// The generic create modal asks for a title and a caption and then hands the person a detail page with
// six separate panels to fill in. Every one of those panels stays — they are where a Post is *edited* —
// but for the first draft the four questions belong on one card, submitted once. The server still does
// all the deciding: the same endpoints the panels use, in the same order an agent's create_post uses
// them, and the readiness card on the detail page says what, if anything, is still missing.
//
// Workflow-scoped like the modal it replaces: it only appears for a Workflow whose asset types name a
// publishable platform, so an engineering list never sees it.

import { useEffect, useMemo, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { Select } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiGet, apiPatch, apiPost, apiPut } from '@/lib/api'
import { humanizeId, workItemDetailPath } from '@/lib/workflows'
import {
  ALLOWED_MEDIA_CONTENT_TYPES,
  isVideoContentType,
  measureVideoMetadata,
  putToSignedUrl,
} from '@/components/workitems/MediaUploadPanel'
import type { CreateWorkItemModalProps } from '@/components/workitems/CreateWorkItemModal'
import type { PublishTargetOption } from '@/components/marketing/PostTargetPicker'

interface CreatedWorkItem {
  id: string
  displayId?: string
}

interface UploadTicket {
  assetId: string
  uploadUrl: string
}

interface PreflightSummary {
  earliestFireTime?: string | null
}

function targetKey(option: PublishTargetOption): string {
  return `${option.platform}${option.connectionId ?? 'manual'}`
}

function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

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
  // Some runtimes list only Etc/UTC; a bare UTC is the one zone everybody expects to find.
  const withUtc = all.includes('UTC') ? all : ['UTC', ...all]
  return withUtc.includes(current) ? withUtc : [current, ...withUtc]
}

/** How far `timeZone` is from UTC at `ts`, in milliseconds — read out of Intl, the one source that knows DST. */
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

/** A wall-clock `datetime-local` value in `timeZone`, as the instant it names. */
export function toInstant(local: string, timeZone: string): string | null {
  if (!local) return null
  const [datePart, timePart] = local.split('T')
  const [y, m, d] = (datePart ?? '').split('-').map(Number)
  const [hh, mm] = (timePart ?? '').split(':').map(Number)
  if (!y || !m || !d || Number.isNaN(hh) || Number.isNaN(mm)) return null
  const guess = Date.UTC(y, m - 1, d, hh, mm)
  // Two passes converge on the zone's offset at that wall-clock time, DST included.
  let instant = guess
  for (let i = 0; i < 2; i++) {
    instant = guess - offsetAt(instant, timeZone)
  }
  return new Date(instant).toISOString()
}

/** The next quarter-hour at or after `earliest` — a tidy calendar slot rather than 14:07. */
export function nextQuarterHour(earliest: Date): Date {
  const slot = new Date(earliest.getTime())
  slot.setSeconds(0, 0)
  slot.setMinutes(Math.ceil(slot.getMinutes() / 15) * 15)
  if (slot.getTime() < earliest.getTime()) slot.setMinutes(slot.getMinutes() + 15)
  return slot
}

export function ComposePostModal({
  open,
  onOpenChange,
  projectId,
  workflowSlug,
  workflowView,
  detailArea,
  noun,
  token,
  onCreated,
}: CreateWorkItemModalProps) {
  const router = useRouter()
  const types = useMemo(() => workflowView?.types ?? [], [workflowView])
  const assetType = workflowView?.assetTypes?.[0] ?? ''
  const [title, setTitle] = useState('')
  const [caption, setCaption] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [options, setOptions] = useState<PublishTargetOption[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [when, setWhen] = useState('')
  const [timeZone, setTimeZone] = useState(browserTimeZone)
  const [saving, setSaving] = useState(false)
  const [step, setStep] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (!open) return
    setTitle('')
    setCaption('')
    setFiles([])
    setSelected(new Set())
    setWhen('')
    setStep(null)
    let cancelled = false
    apiGet<PublishTargetOption[]>(`/api/v2/projects/${projectId}/publish-targets`, token)
      .then((list) => {
        if (!cancelled) setOptions(list)
      })
      .catch((err) => {
        if (!cancelled) toastError(apiErrorMessage(err, 'Could not load the accounts to publish to'))
      })
    return () => {
      cancelled = true
    }
  }, [open, projectId, token])

  const zones = useMemo(() => timeZones(timeZone), [timeZone])
  const grouped = useMemo(() => {
    const byPlatform = new Map<string, PublishTargetOption[]>()
    for (const option of options) {
      const list = byPlatform.get(option.platform) ?? []
      list.push(option)
      byPlatform.set(option.platform, list)
    }
    return [...byPlatform.entries()]
  }, [options])

  function toggle(option: PublishTargetOption) {
    const key = targetKey(option)
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const canSubmit = caption.trim().length > 0 && selected.size > 0 && types.length > 0 && !saving

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    if (!canSubmit) return
    setSaving(true)
    const type = types.includes('POST') ? 'POST' : types[0]!
    const firstLine = caption.split(/\r?\n/).find((l) => l.trim().length > 0)?.trim() ?? caption.trim()
    let created: CreatedWorkItem
    try {
      setStep('Creating…')
      created = await apiPost<CreatedWorkItem>(
        `/api/v2/projects/${projectId}/work-items`,
        {
          type,
          title: title.trim() || (firstLine.length > 80 ? firstLine.slice(0, 77) + '…' : firstLine),
          description: caption.trim(),
          workflow: workflowSlug,
        },
        token
      )
    } catch (err) {
      toastError(apiErrorMessage(err, `Could not create the ${noun.toLowerCase()}`))
      setSaving(false)
      setStep(null)
      return
    }

    // From here on the Post exists: anything that fails is reported and then the page takes over,
    // where the readiness card says exactly what is still missing.
    const base = `/api/v2/projects/${projectId}/work-items/${created.id}`
    const problems: string[] = []
    for (const [index, file] of files.entries()) {
      try {
        setStep(`Uploading ${index + 1} of ${files.length}…`)
        const measured = isVideoContentType(file.type) ? await measureVideoMetadata(file) : null
        const ticket = await apiPost<UploadTicket>(
          `${base}/assets/uploads`,
          {
            type: assetType,
            label: file.name,
            filename: file.name,
            contentType: file.type,
            sizeBytes: file.size,
            ...(measured?.width ? { width: measured.width } : {}),
            ...(measured?.height ? { height: measured.height } : {}),
            ...(measured?.durationSeconds ? { durationSeconds: measured.durationSeconds } : {}),
          },
          token
        )
        await putToSignedUrl(ticket.uploadUrl, file, () => {})
        await apiPost<void>(`${base}/assets/${ticket.assetId}/confirm`, { sizeBytes: file.size }, token)
      } catch (err) {
        problems.push(`${file.name}: ${apiErrorMessage(err, 'upload failed')}`)
      }
    }

    try {
      setStep('Choosing destinations…')
      const targets = options
        .filter((o) => selected.has(targetKey(o)))
        .map((o) => ({ platform: o.platform, ...(o.connectionId ? { connectionId: o.connectionId } : {}) }))
      await apiPut(`${base}/publish-targets`, { targets }, token)
    } catch (err) {
      problems.push(apiErrorMessage(err, 'Could not save the destinations'))
    }

    try {
      setStep('Scheduling…')
      let scheduledFor = toInstant(when, timeZone)
      if (!scheduledFor) {
        // No time given: the server says the earliest the chosen destinations accept.
        const preflight = await apiGet<PreflightSummary>(`${base}/publish-preflight`, token)
        const earliest = preflight.earliestFireTime
          ? new Date(preflight.earliestFireTime)
          : new Date(Date.now() + 15 * 60_000)
        scheduledFor = nextQuarterHour(earliest).toISOString()
      }
      await apiPatch(base, { scheduledFor, scheduleTimezone: timeZone }, token)
    } catch (err) {
      problems.push(apiErrorMessage(err, 'Could not set the schedule'))
    }

    onCreated()
    onOpenChange(false)
    setSaving(false)
    setStep(null)
    if (problems.length > 0) {
      toastError(`${noun} created, but: ${problems.join('; ')}`)
    }
    if (created.displayId) {
      router.push(workItemDetailPath(projectId, detailArea, noun, created.displayId))
    }
  }

  return (
    <Modal
      open={open}
      onOpenChange={(next) => {
        if (!saving) onOpenChange(next)
      }}
      title={`New ${noun}`}
      description={`Say what it says, show what it shows, pick where it goes and when. Everything can be changed on the ${noun.toLowerCase()} afterwards.`}
      footer={
        <div className="flex items-center justify-between gap-2">
          <span className="text-xs text-muted-foreground">{step ?? ''}</span>
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => onOpenChange(false)} disabled={saving}>
              Cancel
            </Button>
            <Button type="submit" form="compose-post" disabled={!canSubmit}>
              {saving ? 'Creating…' : `Create ${noun.toLowerCase()}`}
            </Button>
          </div>
        </div>
      }
    >
      <form id="compose-post" onSubmit={submit} className="space-y-4">
        <div className="space-y-1">
          <Label htmlFor="compose-caption">Caption</Label>
          <Textarea
            id="compose-caption"
            autoFocus
            required
            rows={5}
            value={caption}
            onChange={(e) => setCaption(e.target.value)}
            placeholder={`What should this ${noun.toLowerCase()} say?`}
          />
        </div>

        <div className="space-y-1">
          <Label htmlFor="compose-title">
            Title <span className="text-muted-foreground">(optional; the caption&rsquo;s first line otherwise)</span>
          </Label>
          <Input id="compose-title" value={title} onChange={(e) => setTitle(e.target.value)} />
        </div>

        <div className="space-y-1">
          <Label htmlFor="compose-media">Media</Label>
          <input
            id="compose-media"
            ref={fileInputRef}
            type="file"
            multiple
            accept={ALLOWED_MEDIA_CONTENT_TYPES.join(',')}
            className="sr-only"
            onChange={(e) => {
              setFiles((prev) => [...prev, ...Array.from(e.target.files ?? [])])
              e.target.value = ''
            }}
          />
          <div className="flex flex-wrap items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => fileInputRef.current?.click()}
              disabled={saving}
            >
              {files.length === 0 ? 'Choose files' : 'Add another'}
            </Button>
            {files.length === 0 && (
              <span className="text-xs text-muted-foreground">
                Images and video. Every destination gets all of them unless you customise it on the{' '}
                {noun.toLowerCase()}.
              </span>
            )}
          </div>
          {files.length > 0 && (
            <ul className="mt-2 space-y-1 text-sm">
              {files.map((file, index) => (
                <li
                  key={`${file.name}-${index}`}
                  className="flex items-center justify-between gap-2 rounded-md border border-border px-2 py-1"
                >
                  <span className="truncate">{file.name}</span>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="h-6 px-1.5 text-xs"
                    onClick={() => setFiles((prev) => prev.filter((_, i) => i !== index))}
                    aria-label={`Remove ${file.name}`}
                  >
                    Remove
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <fieldset className="space-y-2">
          <legend className="text-sm font-medium text-foreground">Publish to</legend>
          {grouped.length === 0 ? (
            <p className="text-xs text-muted-foreground">Loading accounts…</p>
          ) : (
            grouped.map(([platform, list]) => (
              <div key={platform} className="space-y-1">
                <p className="text-xs uppercase tracking-wide text-muted-foreground">{humanizeId(platform)}</p>
                {list.map((option) => {
                  const key = targetKey(option)
                  const unhealthy = option.healthStatus === 'UNHEALTHY'
                  return (
                    <label key={key} className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={selected.has(key)}
                        disabled={unhealthy || saving}
                        onChange={() => toggle(option)}
                        aria-label={option.label}
                      />
                      <span className={unhealthy ? 'text-muted-foreground' : undefined}>{option.label}</span>
                      {option.lane === 'MANUAL' && (
                        <span className="text-xs text-muted-foreground">a person posts it by hand</span>
                      )}
                      {unhealthy && (
                        <span className="text-xs text-muted-foreground">reconnect this account first</span>
                      )}
                    </label>
                  )
                })}
              </div>
            ))
          )}
        </fieldset>

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <div className="space-y-1">
            <Label htmlFor="compose-when">
              When <span className="text-muted-foreground">(optional; the next slot the destinations accept otherwise)</span>
            </Label>
            <Input id="compose-when" type="datetime-local" value={when} onChange={(e) => setWhen(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="compose-zone">Time zone</Label>
            <Select id="compose-zone" value={timeZone} onChange={(e) => setTimeZone(e.target.value)}>
              {zones.map((zone) => (
                <option key={zone} value={zone}>
                  {zone}
                </option>
              ))}
            </Select>
          </div>
        </div>
      </form>
    </Modal>
  )
}
