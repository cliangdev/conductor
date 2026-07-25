'use client'

import * as React from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { cn } from '@/lib/utils'

interface SheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children: React.ReactNode
  footer?: React.ReactNode
}

/** A right-anchored slide-over — the sibling of Modal for content that reads better as a panel
 * than a centered dialog (e.g. inspecting one item from a list/canvas without leaving the page
 * context behind a full backdrop-dimmed centered box). Same Dialog primitives and API shape as
 * Modal; only the popup's position/size/transition differ. */
export function Sheet({ open, onOpenChange, title, description, children, footer }: SheetProps) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 bg-black/40 dark:bg-black/60 z-40" />
        <Dialog.Popup
          className={cn(
            'fixed right-0 top-0 z-50 h-full w-full sm:w-[420px]',
            'bg-surface border-l border-border shadow-lg',
            'flex flex-col overflow-hidden',
            'transition-transform duration-200 ease-out translate-x-0',
            'data-[starting-style]:translate-x-full data-[ending-style]:translate-x-full',
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
