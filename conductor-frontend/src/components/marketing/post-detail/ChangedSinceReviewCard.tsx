'use client'

// "Changed since last review": the newest edit to a Creative's slide text that reached this Post in its
// current review round — which Creative, who, when, and the words that changed. A reviewer's own fix says so.

import Link from 'next/link'
import { Card } from '@/components/ui/card'
import { TextChangeRows } from '@/components/marketing/creatives/TextChangeRows'
import { timeAgo } from '@/lib/format'
import type { PostContentChange } from './contentChanges'

export function ChangedSinceReviewCard({ change, projectId }: { change: PostContentChange; projectId: string }) {
  const who = change.actorName || 'Someone'
  const fix = change.source === 'REVIEWER'
  return (
    <Card className="space-y-2 bg-surface-2 px-3 py-2.5" data-testid="changed-since-review">
      <h3 className="text-sm font-medium text-foreground">Changed since last review</h3>
      <p className="text-xs text-muted-foreground">
        {fix ? `${who} fixed the text on ` : `${who} changed the text on `}
        {change.creativeDisplayId ? (
          <Link prefetch={false} href={`/app/projects/${projectId}/marketing/creatives/${change.creativeId}`} className="text-primary hover:underline">
            {change.creativeDisplayId}
          </Link>
        ) : (
          'a Creative'
        )}{' '}
        · {timeAgo(change.createdAt)}
        {fix ? ' · stays in this review round' : change.reviewRoundAfter > change.reviewRoundBefore ? ` · review round ${change.reviewRoundAfter}` : ''}
      </p>
      <TextChangeRows changes={change.textDiff} />
    </Card>
  )
}
