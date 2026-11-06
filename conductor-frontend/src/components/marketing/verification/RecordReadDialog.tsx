'use client'

// Records one direct platform read: what the platform's own UI or API showed for a metric over a range of whole
// days. Checks the form the way the API will, posts it, then shows the verdict of the returned ledger row.
// Reads are append-only, so there is no edit: a wrong read is superseded by recording a newer one.

import { useEffect, useRef, useState } from 'react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { Select } from '@/components/ui/select'
import { Textarea } from '@/components/ui/textarea'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage } from '@/lib/api'
import { CHANNEL_LABELS } from '@/components/marketing/definitions/types'
import { definitionLine } from '@/components/marketing/definitions/definitionLine'
import { MAX_NOTE, MAX_SOURCE_NAME, EMPTY_READ_FORM, readToRequest, validateRead, type ReadFormValues } from './readValidation'
import { recordPlatformRead, type VerificationRow } from './types'
import { MismatchMarker, VerdictBadge } from './VerdictBadge'

export interface RecordReadDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  /** The ledger's rows: the metrics a read can be recorded for. */
  rows: VerificationRow[]
  /** The metric to start on, e.g. from a row's button. */
  initial?: { channel: string; metric: string }
  /** Called once a read is recorded, so the ledger can reload. */
  onRecorded: (row: VerificationRow) => void
}

function startingForm(rows: VerificationRow[], initial?: { channel: string; metric: string }): ReadFormValues {
  const chosen = rows.find((r) => r.channel === initial?.channel && r.metric === initial?.metric) ?? rows[0]
  return { ...EMPTY_READ_FORM, channel: chosen?.channel ?? '', metric: chosen?.metric ?? '' }
}

export function RecordReadDialog({ open, onOpenChange, projectId, rows, initial, onRecorded }: RecordReadDialogProps) {
  const { accessToken } = useAuth()
  const [form, setForm] = useState<ReadFormValues>(() => startingForm(rows, initial))
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<VerificationRow | null>(null)
  const sending = useRef(false)

  useEffect(() => {
    if (!open) return
    setForm(startingForm(rows, initial))
    setError(null)
    setBusy(false)
    setResult(null)
    sending.current = false
    // Reset only when the dialog opens or is pointed at another metric, not when the ledger reloads behind it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, initial?.channel, initial?.metric])

  const set = <K extends keyof ReadFormValues>(key: K, value: ReadFormValues[K]) => setForm((f) => ({ ...f, [key]: value }))

  const channels = [...new Set(rows.map((r) => r.channel))]
  const metrics = rows.filter((r) => r.channel === form.channel)
  const selected = rows.find((r) => r.channel === form.channel && r.metric === form.metric) ?? null

  function changeChannel(channel: string) {
    const first = rows.find((r) => r.channel === channel)
    setForm((f) => ({ ...f, channel, metric: first?.metric ?? '' }))
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (sending.current || !accessToken || !selected) return
    const problem = validateRead(form)
    if (problem) {
      setError(problem)
      return
    }
    sending.current = true
    setBusy(true)
    setError(null)
    try {
      const recorded = await recordPlatformRead(projectId, accessToken, readToRequest(form, selected.unit))
      setResult(recorded)
      onRecorded(recorded)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not record the read — please try again.'))
    } finally {
      sending.current = false
      setBusy(false)
    }
  }

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="Record a platform read"
      description="What the platform itself shows for a metric over whole days. Reads are kept as history; a newer read replaces an older one in the ledger."
    >
      {rows.length === 0 ? (
        <div className="space-y-4">
          <p className="text-sm text-foreground">Connect a channel first: there is no metric to check a read against yet.</p>
          <div className="flex justify-end">
            <Button type="button" variant="outline" size="sm" onClick={() => onOpenChange(false)}>
              Close
            </Button>
          </div>
        </div>
      ) : result ? (
        <div className="space-y-4">
          <div role="status" className="space-y-2 rounded-md border border-border bg-muted/40 p-3">
            <p className="text-sm font-medium text-foreground">
              {result.label} <span className="font-mono text-xs font-normal text-muted-foreground">{result.metricRef}</span>
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <VerdictBadge verdict={result.verdict} />
              {result.mismatch && <MismatchMarker />}
            </div>
            <p className="text-sm text-muted-foreground">{result.trustNote}</p>
          </div>
          <div className="flex justify-end">
            <Button type="button" size="sm" onClick={() => onOpenChange(false)}>
              Done
            </Button>
          </div>
        </div>
      ) : (
        <form onSubmit={submit} className="space-y-4" noValidate>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label htmlFor="read-channel">Channel</Label>
              <Select id="read-channel" value={form.channel} onChange={(e) => changeChannel(e.target.value)}>
                {channels.map((c) => (
                  <option key={c} value={c}>
                    {CHANNEL_LABELS[c] ?? c}
                  </option>
                ))}
              </Select>
            </div>
            <div>
              <Label htmlFor="read-metric">Metric</Label>
              <Select id="read-metric" value={form.metric} onChange={(e) => set('metric', e.target.value)}>
                {metrics.map((m) => (
                  <option key={m.metric} value={m.metric}>
                    {m.label}
                  </option>
                ))}
              </Select>
            </div>
          </div>
          {selected?.definition && <p className="-mt-2 text-xs text-muted-foreground">{definitionLine(selected.definition)}</p>}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label htmlFor="read-from">First day</Label>
              <Input id="read-from" type="date" value={form.from} onChange={(e) => set('from', e.target.value)} />
            </div>
            <div>
              <Label htmlFor="read-to">Last day</Label>
              <Input id="read-to" type="date" value={form.to} onChange={(e) => set('to', e.target.value)} />
            </div>
          </div>
          <div>
            <Label htmlFor="read-value">Value</Label>
            <Input
              id="read-value"
              inputMode="decimal"
              value={form.value}
              onChange={(e) => set('value', e.target.value)}
              placeholder={selected?.unit === 'PERCENT' ? 'e.g. 4 for 4%' : 'As the platform shows it'}
            />
            <p className="mt-1 text-xs text-muted-foreground">
              {selected?.unit === 'PERCENT' ? 'Typed as a percentage.' : 'Zero is a real reading; leave it blank only if you have none.'}
            </p>
          </div>
          <div>
            <Label htmlFor="read-source">Source</Label>
            <Input
              id="read-source"
              value={form.sourceName}
              onChange={(e) => set('sourceName', e.target.value)}
              maxLength={MAX_SOURCE_NAME}
              placeholder="Apple Ads console"
            />
          </div>
          <div>
            <Label htmlFor="read-note">Note (optional)</Label>
            <Textarea id="read-note" value={form.note} onChange={(e) => set('note', e.target.value)} maxLength={MAX_NOTE} rows={2} />
          </div>
          {error && <Alert variant="destructive">{error}</Alert>}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" size="sm" disabled={busy}>
              Record read
            </Button>
          </div>
        </form>
      )}
    </Modal>
  )
}
