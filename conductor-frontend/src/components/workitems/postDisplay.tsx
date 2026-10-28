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

export interface Destination {
  platform: PublishPlatform
  /** True when any target for this platform is FAILED. */
  failed: boolean
  /** Hover text: the state words of this platform's targets. */
  title: string
}

/**
 * The Post's destinations. Prefers the list payload's `publishTargets` (every configured target with its
 * state, so a failed one shows); falls back to the link Assets of what was published
 * (`instagram_post` -> instagram) for payloads that carry no targets.
 */
export function destinationsOf(issue: Pick<Issue, 'externalLinks' | 'publishTargets'>): Destination[] {
  const byPlatform = new Map<PublishPlatform, string[]>()
  const states = new Map<PublishPlatform, string[]>()
  for (const t of issue.publishTargets ?? []) {
    const p = t.platform.trim().toLowerCase() as PublishPlatform
    if (!PLATFORMS.includes(p)) continue
    byPlatform.set(p, [...(byPlatform.get(p) ?? []), t.stateLabel ?? t.state])
    states.set(p, [...(states.get(p) ?? []), t.state])
  }
  if (byPlatform.size === 0) {
    for (const link of issue.externalLinks ?? []) {
      const head = link.type.trim().toLowerCase().split('_')[0] as PublishPlatform
      if (PLATFORMS.includes(head)) byPlatform.set(head, ['Published'])
    }
  }
  return PLATFORMS.filter((p) => byPlatform.has(p)).map((p) => ({
    platform: p,
    failed: (states.get(p) ?? []).includes('FAILED'),
    title: byPlatform.get(p)!.join(', '),
  }))
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

/** The destination marks (brand logos); a failed destination gets a destructive ring and says so on hover. */
export function DestinationMarks({
  issue,
  className,
}: {
  issue: Pick<Issue, 'externalLinks' | 'publishTargets'>
  className?: string
}) {
  const dests = destinationsOf(issue)
  if (dests.length === 0) return null
  return (
    <span className={cn('inline-flex items-center gap-1', className)} data-testid="destination-marks">
      {dests.map((d) => (
        <span
          key={d.platform}
          title={d.title}
          data-failed={d.failed || undefined}
          className={cn('inline-flex rounded-[5px]', d.failed && 'ring-2 ring-destructive')}
        >
          <PlatformIcon platform={d.platform} className="h-4 w-4" />
        </span>
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
