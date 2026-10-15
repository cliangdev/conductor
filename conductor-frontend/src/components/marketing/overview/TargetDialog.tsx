'use client'

// Set or clear the target for one channel metric. PERCENT metrics are typed as whole percentages
// ("4" means 4%) and converted to the API's fraction (0.04) on save; the server's validation message
// is shown inline.

import { useEffect, useState } from 'react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { Select } from '@/components/ui/select'
import { apiErrorMessage } from '@/lib/api'
import { inputToTarget, targetToInput } from './format'
import type { OverviewMetric, TargetDirection } from './types'

export interface TargetDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  metric: OverviewMetric | null
  onSave: (body: { target: number; direction: TargetDirection }) => Promise<void>
  onRemove: () => Promise<void>
}

export function TargetDialog({ open, onOpenChange, metric, onSave, onRemove }: TargetDialogProps) {
  const [text, setText] = useState('')
  const [direction, setDirection] = useState<TargetDirection>('UP')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open || !metric) return
    setText(metric.target ? targetToInput(metric.target.value, metric.unit) : '')
    setDirection(metric.target?.direction ?? metric.goodDirection)
    setError(null)
    setBusy(false)
  }, [open, metric])

  if (!metric) return null
  const percent = metric.unit === 'PERCENT'

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      onOpenChange(false)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not save the target — please try again.'))
    } finally {
      setBusy(false)
    }
  }

  function submit(e: React.FormEvent) {
    e.preventDefault()
    const parsed = inputToTarget(text, metric!.unit)
    if (parsed === null) {
      setError('Enter a number of zero or more.')
      return
    }
    void run(() => onSave({ target: parsed, direction }))
  }

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title={`Target for ${metric.label}`}
      description="Cards show how far the current value is from this number."
    >
      <form onSubmit={submit} className="space-y-4" noValidate>
        <div>
          <Label htmlFor="target-value">{percent ? 'Target (%)' : 'Target'}</Label>
          <Input
            id="target-value"
            inputMode="decimal"
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={percent ? 'e.g. 4 for 4%' : 'e.g. 1000'}
            aria-invalid={error ? true : undefined}
            autoFocus
          />
        </div>
        <div>
          <Label htmlFor="target-direction">Good direction</Label>
          <Select
            id="target-direction"
            value={direction}
            onChange={(e) => setDirection(e.target.value as TargetDirection)}
          >
            <option value="UP">Higher is better</option>
            <option value="DOWN">Lower is better</option>
          </Select>
        </div>
        {error && <Alert variant="destructive">{error}</Alert>}
        <div className="flex flex-wrap items-center justify-between gap-2">
          {metric.target ? (
            <Button type="button" variant="ghost" size="sm" disabled={busy} onClick={() => void run(onRemove)}>
              Remove target
            </Button>
          ) : (
            <span />
          )}
          <div className="flex gap-2">
            <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" size="sm" disabled={busy}>
              Save target
            </Button>
          </div>
        </div>
      </form>
    </Modal>
  )
}
