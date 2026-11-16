'use client'

import * as React from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { X } from 'lucide-react'
import { cn } from '@/lib/utils'

interface SheetProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  children: React.ReactNode
  footer?: React.ReactNode
  /** Which edge the panel slides from. `right` is the default slide-over; `bottom` is the phone sheet. */
  side?: 'right' | 'bottom'
  /** Adds a close button in the header (Escape and a backdrop click always close). */
  showClose?: boolean
  /** Where focus goes when the sheet opens (see Base UI `Dialog.Popup`); the default is the first control. */
  initialFocus?: React.ComponentProps<typeof Dialog.Popup>['initialFocus']
  /** Marks the popup, so callers can find it (e.g. to focus a field inside). */
  'data-sheet'?: string
}

/** A slide-over panel — the sibling of Modal for content that reads better as a panel than a centered
 * dialog (e.g. inspecting one item from a list/canvas without leaving the page context behind a full
 * backdrop-dimmed centered box). Same Dialog primitives and API shape as Modal; only the popup's
 * position/size/transition differ. The Dialog traps and returns focus, locks the page scroll and closes
 * on Escape or a backdrop click. `side="bottom"` is a phone sheet; it keeps clear of the safe-area
 * insets (home indicator, notch). */
export function Sheet({
  open,
  onOpenChange,
  title,
  description,
  children,
  footer,
  side = 'right',
  showClose = false,
  initialFocus,
  'data-sheet': dataSheet,
}: SheetProps) {
  const bottom = side === 'bottom'
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 bg-black/40 dark:bg-black/60 z-40" />
        <Dialog.Popup
          data-sheet={dataSheet}
          initialFocus={initialFocus}
          className={cn(
            'fixed z-50 bg-surface shadow-lg flex flex-col overflow-hidden',
            'transition-transform duration-200 ease-out',
            bottom
              ? [
                  'inset-x-0 bottom-0 max-h-[85dvh] rounded-t-xl border-t border-border',
                  'pb-[env(safe-area-inset-bottom)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]',
                  'translate-y-0 data-[starting-style]:translate-y-full data-[ending-style]:translate-y-full',
                ]
              : [
                  'right-0 top-0 h-full w-full sm:w-[420px] border-l border-border',
                  'pt-[env(safe-area-inset-top)] pb-[env(safe-area-inset-bottom)] pr-[env(safe-area-inset-right)]',
                  'translate-x-0 data-[starting-style]:translate-x-full data-[ending-style]:translate-x-full',
                ]
          )}
        >
          <div className={cn('shrink-0', bottom ? 'px-4 pt-3' : 'px-6 pt-6')}>
            {bottom && <div aria-hidden="true" className="mx-auto mb-2 h-1 w-10 rounded-full bg-border" />}
            <div className="flex items-start justify-between gap-2">
              <Dialog.Title className={cn('font-semibold text-foreground', bottom ? 'text-base' : 'text-lg')}>
                {title}
              </Dialog.Title>
              {showClose && (
                <Dialog.Close
                  aria-label="Close"
                  className="-mr-1.5 -mt-1 rounded-md p-1.5 text-muted-foreground hover:bg-muted hover:text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <X className="h-4 w-4" />
                </Dialog.Close>
              )}
            </div>
            {description && (
              <Dialog.Description className="mt-1 text-sm text-muted-foreground">
                {description}
              </Dialog.Description>
            )}
          </div>
          <div className={cn('mt-4 flex-1 overflow-y-auto min-h-0', bottom ? 'px-4 pb-4' : 'px-6 pb-6')}>
            {children}
          </div>
          {footer && (
            <div className={cn('shrink-0 border-t border-border py-4', bottom ? 'px-4' : 'px-6')}>{footer}</div>
          )}
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
