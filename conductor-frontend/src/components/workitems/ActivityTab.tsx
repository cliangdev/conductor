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
import { TextChangeRows } from '@/components/marketing/creatives/TextChangeRows'
import type { PostContentChange } from '@/components/marketing/post-detail/contentChanges'

type ActivityItem =
  | { kind: 'comment'; id: string; authorName: string; content: string; at: string; resolved: boolean }
  | { kind: 'reply'; id: string; authorName: string; content: string; at: string }
  | { kind: 'review'; id: string; name: string; avatarUrl?: string; verdict: DetailReview['verdict']; body?: string; at: string }
  | { kind: 'content'; id: string; change: PostContentChange; at: string }

function buildTimeline(comments: Comment[], reviews: DetailReview[], contentChanges: PostContentChange[]): ActivityItem[] {
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
  for (const c of contentChanges) items.push({ kind: 'content', id: c.id, change: c, at: c.createdAt })
  return items.sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime())
}

/** The icon alone left a row reading as a name and a time; the verdict is the event. */
function verdictVerb(verdict: DetailReview['verdict']): string {
  switch (verdict) {
    case 'APPROVED':
      return 'approved'
    case 'CHANGES_REQUESTED':
      return 'requested changes'
    default:
      return 'commented'
  }
}

/** What a slide-text change did to the Post's review: a new round, a fix inside the round, or neither. */
function contentChangeNote(c: PostContentChange): string {
  if (c.source === 'REVIEWER') return `fixed during review round ${c.reviewRoundAfter}`
  if (c.reviewRoundAfter > c.reviewRoundBefore) return `opened review round ${c.reviewRoundAfter}`
  return 'updated the Post'
}

export function ActivityTab({
  comments,
  reviews,
  contentChanges = [],
}: {
  comments: Comment[]
  reviews: DetailReview[]
  /** Slide-text edits carried onto this Post, newest first (Posts only). */
  contentChanges?: PostContentChange[]
}) {
  const items = buildTimeline(comments, reviews, contentChanges)

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
            name={item.kind === 'review' ? item.name : item.kind === 'content' ? (item.change.actorName ?? 'Someone') : item.authorName}
            avatarUrl={item.kind === 'review' ? item.avatarUrl : undefined}
            size={5}
            className="mt-0.5"
          />
          <div className="min-w-0 flex-1">
            <div className="flex items-center gap-1.5 flex-wrap text-sm">
              <span className="font-medium text-foreground">
                {item.kind === 'review' ? item.name : item.kind === 'content' ? (item.change.actorName ?? 'Someone') : item.authorName}
              </span>
              {item.kind === 'content' && (
                <span className="text-xs text-muted-foreground">
                  changed the slide text{item.change.creativeDisplayId ? ` on ${item.change.creativeDisplayId}` : ''} · {contentChangeNote(item.change)}
                </span>
              )}
              {item.kind === 'review' && (
                <>
                  <VerdictIcon verdict={item.verdict} />
                  <span className="text-xs text-muted-foreground">{verdictVerb(item.verdict)}</span>
                </>
              )}
              {item.kind === 'reply' && <span className="text-xs text-muted-foreground">replied</span>}
              {item.kind === 'comment' && item.resolved && (
                <span className="text-xs text-status-approved font-medium">Resolved</span>
              )}
              <span className="text-xs text-foreground-subtle">{timeAgo(item.at)}</span>
            </div>
            {item.kind === 'content' ? (
              <TextChangeRows changes={item.change.textDiff} className="mt-1" />
            ) : (
              (item.kind !== 'review' || item.body) && (
                <p className="mt-0.5 text-sm text-muted-foreground whitespace-pre-wrap leading-relaxed">
                  {item.kind === 'review' ? item.body : item.content}
                </p>
              )
            )}
          </div>
        </div>
      ))}
    </div>
  )
}
