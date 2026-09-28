'use client'

// A view-only, near-fullscreen dialog shared by the Creative editor's placement viewer
// (PlacementViewerBoard in CreativeEditor.tsx) and the Renders panel's frame viewer — the two
// places a Creative's real pixels are worth seeing larger than the small preview/thumbnail grid.
// Built on the same base-ui Dialog primitive as Modal (see MermaidFullscreenViewer for the other
// precedent of going straight to Dialog instead of the fixed-width Modal wrapper), sized to content
// up to ~90% of the viewport rather than a fixed max width.

import { useEffect } from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { ChevronLeftIcon, ChevronRightIcon, XIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

export interface FullSizeViewerProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  subtitle?: string
  /** Omit to hide that arrow/button — the caller decides whether there is a sibling to step to. */
  onPrev?: () => void
  onNext?: () => void
  children: React.ReactNode
}

export function FullSizeViewer({ open, onOpenChange, title, subtitle, onPrev, onNext, children }: FullSizeViewerProps) {
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'ArrowLeft' && onPrev) {
        e.preventDefault()
        onPrev()
      } else if (e.key === 'ArrowRight' && onNext) {
        e.preventDefault()
        onNext()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, onPrev, onNext])

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-black/60 dark:bg-black/80" />
        <Dialog.Popup
          className={cn(
            'fixed left-1/2 top-1/2 z-50 -translate-x-1/2 -translate-y-1/2',
            'flex max-h-[92vh] max-w-[92vw] flex-col overflow-hidden rounded-lg border border-border bg-popover shadow-lg outline-none',
          )}
        >
          <div className="flex shrink-0 items-center justify-between gap-3 border-b border-border px-4 py-3">
            <div className="min-w-0">
              <Dialog.Title className="truncate text-sm font-semibold text-foreground">{title}</Dialog.Title>
              {subtitle && <Dialog.Description className="text-xs text-muted-foreground">{subtitle}</Dialog.Description>}
            </div>
            <Button variant="ghost" size="icon" aria-label="Close" onClick={() => onOpenChange(false)}>
              <XIcon className="h-4 w-4" aria-hidden />
            </Button>
          </div>

          <div className="flex min-h-0 flex-1 items-center justify-center gap-1 overflow-auto p-4">
            {onPrev && (
              <Button variant="ghost" size="icon" aria-label="Previous" onClick={onPrev} className="shrink-0">
                <ChevronLeftIcon className="h-5 w-5" aria-hidden />
              </Button>
            )}
            <div className="flex items-center justify-center">{children}</div>
            {onNext && (
              <Button variant="ghost" size="icon" aria-label="Next" onClick={onNext} className="shrink-0">
                <ChevronRightIcon className="h-5 w-5" aria-hidden />
              </Button>
            )}
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
