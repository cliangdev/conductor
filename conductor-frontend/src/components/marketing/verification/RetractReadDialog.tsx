'use client'

// Retracts one wrong platform read: it stays in the history but stops counting, so the previous read (if any)
// applies. The reason is required and kept with the read. One-way: there is no un-retract.

import { useEffect, useRef, useState } from 'react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { Textarea } from '@/components/ui/textarea'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage } from '@/lib/api'
import { MAX_NOTE } from './readValidation'
import { retractPlatformRead, type VerificationRow } from './types'

export interface RetractReadDialogProps {
  /** The row whose latest platform read is being retracted; null when closed. */
  row: VerificationRow | null
  onOpenChange: (open: boolean) => void
  projectId: string
  /** Called once the read is retracted, so the ledger can reload. */
  onRetracted: () => void
}

export function RetractReadDialog({ row, onOpenChange, projectId, onRetracted }: RetractReadDialogProps) {
  const { accessToken } = useAuth()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const sending = useRef(false)
  const readId = row?.platformRead?.id

  useEffect(() => {
    setReason('')
    setError(null)
    setBusy(false)
    sending.current = false
  }, [readId])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (sending.current || !accessToken || !readId) return
    const trimmed = reason.trim()
    if (!trimmed) {
      setError('Say why the read is wrong.')
      return
    }
    sending.current = true
    setBusy(true)
    setError(null)
    try {
      await retractPlatformRead(projectId, accessToken, readId, trimmed)
      onRetracted()
      onOpenChange(false)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not retract the read — please try again.'))
    } finally {
      sending.current = false
      setBusy(false)
    }
  }

  return (
    <Modal
      open={row !== null}
      onOpenChange={onOpenChange}
      title="Retract this platform read"
      description="The read stays in the history but no longer counts: the previous read of this metric applies, or none. This cannot be undone."
    >
      <form onSubmit={submit} className="space-y-4" noValidate>
        {row?.platformRead && (
          <p className="text-sm text-foreground">
            {`${row.label}: read from ${row.platformRead.sourceName}`}
          </p>
        )}
        <div>
          <Label htmlFor="retract-reason">Reason</Label>
          <Textarea
            id="retract-reason"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={MAX_NOTE}
            rows={3}
            placeholder="e.g. This app no longer uses that platform, so it reads 0"
          />
        </div>
        {error && <Alert variant="destructive">{error}</Alert>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" size="sm" disabled={busy}>
            Retract read
          </Button>
        </div>
      </form>
    </Modal>
  )
}
