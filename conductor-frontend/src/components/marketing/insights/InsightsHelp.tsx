'use client'

// "How to read these numbers" — a plain-language key for the What's working panel. Views are not the same
// unit on every platform, and a feed that shows posts to strangers (TikTok, YouTube Shorts) racks up views
// that rarely turn into likes, so a high-view platform can show a lower engagement rate. Keep the copy in
// step with MarketingInsightsQueryService (engagement rate = engagements / views) and LOW_SAMPLE_ENGAGEMENTS.

import { CircleHelpIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { LOW_SAMPLE_ENGAGEMENTS } from './types'

export function InsightsHelpToggle({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  return (
    <Button
      variant="ghost"
      size="sm"
      className="ml-auto gap-1.5 text-muted-foreground"
      aria-expanded={open}
      aria-controls="insights-help"
      onClick={onToggle}
    >
      <CircleHelpIcon className="h-4 w-4" aria-hidden />
      {open ? 'Hide help' : 'How to read these numbers'}
    </Button>
  )
}

const TERMS: { term: string; body: string }[] = [
  {
    term: 'Views',
    body:
      'How many times a post was shown or played, as each platform counts it. TikTok and YouTube Shorts count a ' +
      'view after about a second of play in the feed. Instagram and Facebook count each time the post appears, ' +
      'replays included. So a view is not quite the same thing on every platform.',
  },
  {
    term: 'Median views',
    body:
      'Views for a typical post: half of the posts did better, half did worse. Unlike the total, one hit post ' +
      'does not pull it up. This is the best measure of how far each platform carries a post.',
  },
  {
    term: 'Engagements',
    body:
      'Likes, comments, shares and saves added together. Facebook reactions count as likes. Some platforms do ' +
      'not report shares or saves; the notes at the bottom of the page say which.',
  },
  {
    term: 'Engagement rate',
    body:
      'Engagements divided by views: out of everyone who saw a post, the share who did something with it.',
  },
  {
    term: 'Avg view %',
    body: 'How much of a video people watched, on average. Only YouTube reports it.',
  },
]

export function InsightsHelp() {
  return (
    <section
      id="insights-help"
      aria-label="How to read these numbers"
      className="rounded-lg border border-border bg-surface-2 p-4 text-sm"
    >
      <dl className="grid gap-x-6 gap-y-3 sm:grid-cols-2">
        {TERMS.map(({ term, body }) => (
          <div key={term}>
            <dt className="font-medium text-foreground">{term}</dt>
            <dd className="mt-0.5 text-muted-foreground">{body}</dd>
          </div>
        ))}
      </dl>

      <h3 className="mt-5 font-medium text-foreground">Why more views can mean a lower rate</h3>
      <p className="mt-1 text-muted-foreground">
        TikTok and YouTube Shorts show posts to strangers scrolling a feed. That brings lots of views, but most
        of those people swipe past without reacting, so the rate is low. Instagram and Facebook mostly show
        posts to people who already follow you. Fewer people see them, but a bigger share of those people react,
        so the rate is high. Look at the Median views column to see the difference in reach.
      </p>
      <p className="mt-2 text-muted-foreground">
        Compare a platform with itself over time rather than with other platforms. A rising rate on TikTok means
        strangers are starting to stop and react. A falling rate on Instagram can simply mean the post reached
        beyond your followers.
      </p>

      <h3 className="mt-5 font-medium text-foreground">Small numbers</h3>
      <p className="mt-1 text-muted-foreground">
        A rate built on fewer than {LOW_SAMPLE_ENGAGEMENTS} engagements is greyed out. A post with 2 views and 2
        likes has a 100% rate, so a handful of likes either way can swing the number a lot.
      </p>
    </section>
  )
}
