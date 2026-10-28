'use client'

// Display helpers shared by the publishing-workflow row and grid card: destination marks, the
// scheduled-time label and the media thumbnail slot.
//
// The Work Item list payload carries `externalLinks` (a link Asset per published destination, typed
// `<platform>_post`), `scheduledFor` / `scheduleTimezone` and `publishOnApproval`. It does NOT carry the
// Post's media, its configured (not yet published) destinations or its Creative, so those render from
// what is present and stay empty otherwise.

import { ImageIcon } from 'lucide-react'
import { PlatformIcon } from '@/components/marketing/destinations/PlatformIcon'
import type { PublishPlatform } from '@/components/marketing/destinations/types'
import type { Issue } from '@/components/workitems/listTypes'
import { cn } from '@/lib/utils'

const PLATFORMS: PublishPlatform[] = ['facebook', 'instagram', 'youtube', 'tiktok']

/** Platforms a Post has been published to, from its link Assets (`instagram_post` -> instagram). */
export function destinationsOf(issue: Pick<Issue, 'externalLinks'>): PublishPlatform[] {
  const found = new Set<PublishPlatform>()
  for (const link of issue.externalLinks ?? []) {
    const head = link.type.trim().toLowerCase().split('_')[0] as PublishPlatform
    if (PLATFORMS.includes(head)) found.add(head)
  }
  return PLATFORMS.filter((p) => found.has(p))
}

/** "Oct 28, 3:00 PM" in the Post's own timezone when it has one; "On approval"; or null. */
export function scheduleLabel(issue: Pick<Issue, 'scheduledFor' | 'scheduleTimezone' | 'publishOnApproval'>): string | null {
  if (issue.scheduledFor) {
    const d = new Date(issue.scheduledFor)
    if (!Number.isNaN(d.getTime())) {
      const opts: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }
      try {
        return new Intl.DateTimeFormat('en-US', { ...opts, timeZone: issue.scheduleTimezone ?? undefined }).format(d)
      } catch {
        return new Intl.DateTimeFormat('en-US', opts).format(d)
      }
    }
  }
  return issue.publishOnApproval ? 'On approval' : null
}

/** The destination marks (brand logos), or nothing when none are known. */
export function DestinationMarks({ issue, className }: { issue: Pick<Issue, 'externalLinks'>; className?: string }) {
  const platforms = destinationsOf(issue)
  if (platforms.length === 0) return null
  return (
    <span className={cn('inline-flex items-center gap-1', className)} data-testid="destination-marks">
      {platforms.map((p) => (
        <PlatformIcon key={p} platform={p} className="h-4 w-4" />
      ))}
    </span>
  )
}

/**
 * The media thumbnail slot. The list payload has no media yet, so `src` is normally empty and this
 * renders a neutral placeholder that keeps rows and cards aligned.
 */
export function PostThumb({ src, className }: { src?: string | null; className?: string }) {
  return (
    <span
      aria-hidden
      className={cn(
        'flex shrink-0 items-center justify-center overflow-hidden rounded-md border border-border bg-surface-3 text-foreground-subtle',
        className,
      )}
    >
      {src ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={src} alt="" className="h-full w-full object-cover" />
      ) : (
        <ImageIcon className="h-4 w-4" />
      )}
    </span>
  )
}
