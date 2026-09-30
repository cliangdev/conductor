'use client'

// COND-24 creative-ease-of-use: a calm, dismissible nudge shown on the Creatives library and at the
// top of the Creative editor when the relevant kit (the Creative's own, or the workspace default on
// the library page) still looks unconfigured — see brandKitStatus.ts. Never blocks anything; it is
// pure encouragement, per the audience-layers model's "Do / read" layer staying content-plus-one-
// action (docs/design-system.md) — this is the one quiet exception, and it dismisses itself away.

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { XIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { CopyableCode } from '@/components/ui/copyable-code'
import { cn } from '@/lib/utils'
import { isBrandKitUnconfigured } from './brandKitStatus'
import type { BrandKit } from './types'

// No URL in the copyable text itself — a placeholder like example.com reads as a real, copy-pasteable
// address, and Claude will ask for the actual site URL when it needs it.
const ASK_CLAUDE_PROMPT = 'Set up our brand kit in Conductor from our website'

function storageKey(kitId: string): string {
  return `conductor.brandSetupNoticeDismissed.${kitId}`
}

function readDismissed(kitId: string): boolean {
  try {
    return localStorage.getItem(storageKey(kitId)) === '1'
  } catch {
    return false
  }
}

function writeDismissed(kitId: string): void {
  try {
    localStorage.setItem(storageKey(kitId), '1')
  } catch {
    // Private window or blocked storage — worst case the notice reappears next visit. Not worth a toast.
  }
}

export interface BrandSetupNoticeProps {
  projectId: string
  /** The Creative's own kit on the editor page; the workspace default kit on the library page. */
  kit: BrandKit | null | undefined
  className?: string
}

export function BrandSetupNotice({ projectId, kit, className }: BrandSetupNoticeProps) {
  const [dismissed, setDismissed] = useState(true)

  useEffect(() => {
    setDismissed(kit ? readDismissed(kit.id) : true)
  }, [kit])

  if (!kit || !isBrandKitUnconfigured(kit) || dismissed) return null

  return (
    <Alert variant="info" data-testid="brand-setup-notice" className={cn('relative pr-9', className)}>
      <div className="space-y-2">
        <p>
          Set up your brand first — colours, font and logo make every creative look like yours.{' '}
          <Link
            href={`/app/projects/${projectId}/settings/brand`}
            className="underline underline-offset-2 hover:no-underline"
          >
            Settings → Brand
          </Link>
        </p>
        <div className="max-w-md space-y-1">
          <p className="text-xs text-muted-foreground">Or ask Claude — it&apos;ll ask for your site&apos;s URL:</p>
          <CopyableCode text={ASK_CLAUDE_PROMPT} label="Copy brand setup prompt" />
        </div>
      </div>
      <button
        type="button"
        onClick={() => {
          writeDismissed(kit.id)
          setDismissed(true)
        }}
        aria-label="Dismiss"
        className="absolute right-2 top-2 rounded p-0.5 text-muted-foreground hover:text-foreground"
      >
        <XIcon className="h-4 w-4" aria-hidden />
      </button>
    </Alert>
  )
}
