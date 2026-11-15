'use client'

// The destination marks on a calendar card: one brand mark per place an item is going out, linked to
// the live thing when it has one. Lives apart from WorkItemCalendarView so that view stays free of any
// one domain's vocabulary — it only knows "an item may carry marks", not what they are.
//
// Matching rule: an external link belongs to a destination when its Asset type, lower-cased, starts
// with `<destination>_` (`instagram_post`, `tiktok_post`, ...). Links that match no destination (an
// Issue's `github_pr`, say) fall through to the generic ExternalLinkCell, so a Workflow with no
// destinations renders exactly as before.

import { PlatformIcon } from '@/components/marketing/destinations/PlatformIcon'
import type { PublishPlatform } from '@/components/marketing/destinations/types'
import { ExternalLinkCell } from './ExternalLinkCell'
import type { WorkItemExternalLink, WorkItemPublishTarget } from './listTypes'

/** The destination-bearing field a calendar item may carry (the list response already supplies it). */
export interface CalendarDestinationFields {
  publishTargets?: WorkItemPublishTarget[]
}

const NAMES: Record<string, string> = {
  facebook: 'Facebook',
  instagram: 'Instagram',
  youtube: 'YouTube',
  tiktok: 'TikTok',
}

function displayName(platform: string): string {
  return NAMES[platform] ?? platform.charAt(0).toUpperCase() + platform.slice(1)
}

export function CalendarDestinationMarks({
  item,
}: {
  item: CalendarDestinationFields & { externalLinks?: WorkItemExternalLink[] }
}) {
  const remaining = [...(item.externalLinks ?? [])]
  const marks = (item.publishTargets ?? []).map((target, index) => {
    const platform = target.platform.trim().toLowerCase()
    const at = remaining.findIndex((l) => l.type.toLowerCase().startsWith(`${platform}_`))
    const link = at >= 0 ? remaining.splice(at, 1)[0] : undefined
    return { key: `${platform}-${index}`, platform, link, failed: target.state === 'FAILED' }
  })

  if (marks.length === 0 && remaining.length === 0) return null

  return (
    <span className="relative z-10 flex shrink-0 items-center gap-1.5">
      {marks.map(({ key, platform, link, failed }) => {
        const name = displayName(platform)
        const label = link
          ? `Open on ${name}${failed ? ' (failed)' : ''}`
          : `${name}${failed ? ' failed' : ''}`
        const icon = (
          <span className="relative inline-flex">
            <PlatformIcon platform={platform as PublishPlatform} className="h-3.5 w-3.5 rounded-[4px]" />
            {failed && (
              <span
                data-testid={`mark-failed-${platform}`}
                className="absolute -right-0.5 -top-0.5 h-1.5 w-1.5 rounded-full bg-destructive ring-1 ring-surface-raised"
              />
            )}
          </span>
        )
        return link ? (
          <a
            key={key}
            href={link.url}
            target="_blank"
            rel="noopener noreferrer"
            title={label}
            aria-label={label}
            className="rounded-sm outline-none transition-opacity hover:opacity-80 focus-visible:ring-2 focus-visible:ring-ring"
          >
            {icon}
          </a>
        ) : (
          <span key={key} role="img" aria-label={label} title={label}>
            {icon}
          </span>
        )
      })}
      <ExternalLinkCell links={remaining} />
    </span>
  )
}
