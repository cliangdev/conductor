'use client'

// Where a Work Item ended up outside Conductor, on a list or calendar surface.
//
// It exists because of a gap that made the publishing pipeline quietly annoying: a Post's permalink was
// recorded, rendered once on the Post detail page, and nowhere else. The calendar — MARKETING's own
// default view — and the list both showed a "Published" badge with no way to reach the thing that was
// published. A human looking at a month of green chips had to open each one to answer "where is it?".
//
// Deliberately domain-free, like every other Work Item surface: it renders the item's link Assets,
// whatever they are. An Issue's `github_pr` gets the same affordance as a Post's `instagram_post`, and
// nothing here knows which is which.

import { ExternalLink } from 'lucide-react'
import type { WorkItemExternalLink } from '@/components/workitems/listTypes'

/** The host, for a tooltip — `instagram.com` reads as a place; the full URL reads as noise. */
function hostOf(url: string): string {
  try {
    return new URL(url).host.replace(/^www\./, '')
  } catch {
    return url
  }
}

function describe(link: WorkItemExternalLink): string {
  return link.label ? `${link.label} — ${hostOf(link.url)}` : hostOf(link.url)
}

/**
 * One icon when there is one link, one per link when there are a few. No collapsing into a count: two
 * accounts is the common case for a Post, and "2 links" would put the trip back that this removes.
 */
export function ExternalLinkCell({ links }: { links?: WorkItemExternalLink[] }) {
  if (!links || links.length === 0) return null

  return (
    <span className="flex items-center gap-1">
      {links.map((link) => (
        <a
          key={link.url}
          href={link.url}
          target="_blank"
          rel="noopener noreferrer"
          title={describe(link)}
          aria-label={`Open ${describe(link)}`}
          // stopPropagation, not preventDefault: on the calendar this sits inside a chip that is itself
          // a link to the Work Item, and without it a click would navigate to both.
          onClick={(e) => e.stopPropagation()}
          className="text-muted-foreground transition-colors hover:text-primary"
        >
          <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
        </a>
      ))}
    </span>
  )
}
