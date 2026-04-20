'use client'

import * as React from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { cn } from '@/lib/utils'

interface ModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children: React.ReactNode
  footer?: React.ReactNode
}

export function Modal({ open, onOpenChange, title, description, children, footer }: ModalProps) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 bg-black/40 dark:bg-black/60 z-40" />
        <Dialog.Popup
          className={cn(
            'fixed left-1/2 top-1/2 z-50 -translate-x-1/2 -translate-y-1/2',
            'w-full max-w-md mx-4 rounded-lg bg-popover border border-border shadow-lg',
            'max-h-[90vh] flex flex-col overflow-hidden',
          )}
        >
          <div className="px-6 pt-6 shrink-0">
            <Dialog.Title className="text-lg font-semibold text-foreground">{title}</Dialog.Title>
            {description && (
              <Dialog.Description className="mt-1 text-sm text-muted-foreground">
                {description}
              </Dialog.Description>
            )}
          </div>
          <div className="px-6 mt-4 pb-6 flex-1 overflow-y-auto min-h-0">
            {children}
          </div>
          {footer && (
            <div className="shrink-0 border-t border-border px-6 py-4">{footer}</div>
          )}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
