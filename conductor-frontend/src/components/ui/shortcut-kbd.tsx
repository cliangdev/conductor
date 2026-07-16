'use client'

// Platform-aware ⌘K / Ctrl+K hint, shared by the sidebar's search button and the command palette
// so both the bordered kbd chip and the Mac/Windows label live in exactly one place.

import { useEffect, useState } from 'react'
import { cn } from '@/lib/utils'

function detectMac(): boolean {
  if (typeof navigator === 'undefined') return false
  return /Mac|iPhone|iPad|iPod/.test(navigator.platform || navigator.userAgent)
}

/** "⌘K" on Mac, "Ctrl K" elsewhere. Resolves after mount — `navigator` isn't available during SSR. */
export function useModKeyLabel(key = 'K'): string {
  const [isMac, setIsMac] = useState(false)
  useEffect(() => setIsMac(detectMac()), [])
  return isMac ? `⌘${key}` : `Ctrl ${key}`
}

/** The bordered kbd chip idiom used everywhere a keyboard shortcut is hinted. */
export function ShortcutKbd({ children, className }: { children: React.ReactNode; className?: string }) {
  return (
    <kbd
      className={cn(
        'inline-flex items-center rounded border border-border bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground',
        className,
      )}
    >
      {children}
    </kbd>
  )
}
