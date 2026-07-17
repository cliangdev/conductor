'use client'

// Client-side interleave of a Work Item's comments (including replies and resolved threads, shown
// compactly) and submitted reviews, newest first. No new APIs — both lists are already fetched by
// WorkItemDetailView for the properties panel / doc viewer.

import { UserAvatar } from '@/components/workitems/UserAvatar'
import { VerdictIcon } from '@/components/reviews/verdict'
import { EmptyState } from '@/components/ui/empty-state'
import { timeAgo } from '@/lib/format'
import { Activity } from 'lucide-react'
import type { Comment } from '@/components/comments/types'
import type { DetailReview } from '@/components/workitems/detailTypes'

type ActivityItem =
  | { kind: 'comment'; id: string; authorName: string; content: string; at: string; resolved: boolean }
  | { kind: 'reply'; id: string; authorName: string; content: string; at: string }
  | { kind: 'review'; id: string; name: string; avatarUrl?: string; verdict: DetailReview['verdict']; body?: string; at: string }

function buildTimeline(comments: Comment[], reviews: DetailReview[]): ActivityItem[] {
  const items: ActivityItem[] = []
  for (const c of comments) {
    items.push({
      kind: 'comment',
      id: c.id,
      authorName: c.authorName,
      content: c.content,
      at: c.createdAt,
      resolved: !!c.resolvedAt,
    })
    for (const r of c.replies) {
      items.push({ kind: 'reply', id: r.id, authorName: r.authorName, content: r.content, at: r.createdAt })
    }
  }
  for (const r of reviews) {
    items.push({
      kind: 'review',
      id: `${r.reviewerId}-${r.submittedAt}`,
      name: r.name,
      avatarUrl: r.avatarUrl,
      verdict: r.verdict,
      body: r.body,
      at: r.submittedAt,
    })
  }
  return items.sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime())
}

export function ActivityTab({ comments, reviews }: { comments: Comment[]; reviews: DetailReview[] }) {
  const items = buildTimeline(comments, reviews)

  if (items.length === 0) {
    return <EmptyState icon={Activity} title="No activity yet" description="Comments and reviews will show up here." />
  }

  return (
    <div className="flex flex-col divide-y divide-border">
      {items.map((item) => (
        <div
          key={`${item.kind}-${item.id}`}
          data-testid={`activity-${item.kind}-${item.id}`}
          className="py-3 flex items-start gap-2.5"
        >
          <UserAvatar
            name={item.kind === 'review' ? item.name : item.authorName}
            avatarUrl={item.kind === 'review' ? item.avatarUrl : undefined}
            size={5}
            className="mt-0.5"
          />
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-1.5 flex-wrap text-sm">
              <span className="font-medium text-foreground">
                {item.kind === 'review' ? item.name : item.authorName}
              </span>
              {item.kind === 'review' && <VerdictIcon verdict={item.verdict} />}
              {item.kind === 'reply' && <span className="text-xs text-muted-foreground">replied</span>}
              {item.kind === 'comment' && item.resolved && (
                <span className="text-xs text-status-approved font-medium">Resolved</span>
              )}
              <span className="text-xs text-foreground-subtle">{timeAgo(item.at)}</span>
            </div>
            {(item.kind !== 'review' || item.body) && (
              <p className="mt-0.5 text-sm text-muted-foreground whitespace-pre-wrap leading-relaxed">
                {item.kind === 'review' ? item.body : item.content}
              </p>
            )}
          </div>
        </div>
      ))}
    </div>
  )
}
