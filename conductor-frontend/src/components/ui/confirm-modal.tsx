'use client'

import type { ReactNode } from 'react'
import { Modal } from '@/components/ui/modal'
import { Button } from '@/components/ui/button'

export interface ConfirmModalProps {
  open: boolean
  title: string
  description?: string
  /** Body content below the description — most callers pass a short paragraph of context. */
  children?: ReactNode
  confirmLabel: string
  /** Shown on the confirm button in place of confirmLabel while `busy` is true (e.g. "Deleting…"). */
  busyLabel?: string
  cancelLabel?: string
  /** Confirm button style. Defaults to the destructive (red) treatment used by delete/discard/remove;
   *  pass false for confirmations that aren't destructive (e.g. disabling something reversibly). */
  destructive?: boolean
  busy?: boolean
  onConfirm: () => void
  onCancel: () => void
}

/** Shared confirm/cancel modal — the standard two-button footer (Cancel + Confirm) used by every
 *  destructive-ish confirmation in the app (delete, discard, disable, remove…), built on the base
 *  `Modal`. Callers own the `open`/`busy` state and the confirm/cancel handlers. */
export function ConfirmModal({
  open,
  title,
  description,
  children,
  confirmLabel,
  busyLabel,
  cancelLabel = 'Cancel',
  destructive = true,
  busy = false,
  onConfirm,
  onCancel,
}: ConfirmModalProps) {
  return (
    <Modal
      open={open}
      onOpenChange={(next) => {
        if (!next) onCancel()
      }}
      title={title}
      description={description}
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" size="sm" onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button
            variant={destructive ? 'destructive' : 'default'}
            size="sm"
            onClick={onConfirm}
            disabled={busy}
          >
            {busy && busyLabel ? busyLabel : confirmLabel}
          </Button>
        </div>
      }
    >
      {children}
    </Modal>
  )
}
