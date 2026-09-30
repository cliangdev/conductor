'use client'

// COND-24 creative-ease-of-use: the shared shape for every "copy this prompt/command" affordance
// (Renders panel, Creatives empty state, the brand setup nudge's "ask Claude" prompt) — one
// treatment per design-system.md principle 5, instead of each surface re-typing its own
// code-chip-plus-button markup and its own clipboard try/catch.

import { CopyIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { toastError, toastSuccess } from '@/components/ui/toast'
import { cn } from '@/lib/utils'

export interface CopyableCodeProps {
  text: string
  /** aria-label on the copy button; defaults to a generic "Copy". */
  label?: string
  className?: string
}

export function CopyableCode({ text, label, className }: CopyableCodeProps) {
  function handleCopy() {
    navigator.clipboard
      ?.writeText(text)
      .then(() => toastSuccess('Copied'))
      .catch(() => toastError('Could not copy — select and copy manually'))
  }

  return (
    <div
      className={cn(
        'flex items-center gap-2 rounded-md border border-border bg-surface-2 px-2 py-1.5',
        className,
      )}
    >
      <code className="flex-1 truncate text-xs">{text}</code>
      <Button variant="ghost" size="sm" onClick={handleCopy} aria-label={label ?? 'Copy'}>
        <CopyIcon className="h-3.5 w-3.5" aria-hidden />
      </Button>
    </div>
  )
}
