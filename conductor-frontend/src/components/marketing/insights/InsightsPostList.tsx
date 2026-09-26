// "Top posts" / "Needs a rethink" — one row per destination, best or worst engagement rate first.

import Link from 'next/link'
import { ExternalLinkIcon } from 'lucide-react'
import { PlatformIcon } from '@/components/marketing/destinations/PlatformIcon'
import type { PublishPlatform } from '@/components/marketing/destinations/types'
import { compactCount } from '@/components/marketing/destinations/DestinationMetrics'
import { humanizeId } from '@/lib/workflows'
import { engagementRateLabel, type InsightsPost } from './types'

function postTitle(post: InsightsPost): string {
  return post.title || post.displayId || post.workItemId
}

/** One row's title: a link to the Post page when a Marketing workflow route is known, plain text otherwise. */
function PostTitle({ post, href }: { post: InsightsPost; href: string | null }) {
  const title = postTitle(post)
  if (!href) return <span className="text-foreground">{title}</span>
  return (
    <Link href={href} className="text-foreground hover:underline">
      {title}
    </Link>
  )
}

export function InsightsPostList({
  title,
  posts,
  hrefFor,
}: {
  title: string
  posts: InsightsPost[]
  hrefFor: (post: InsightsPost) => string | null
}) {
  if (posts.length === 0) return null

  return (
    <div>
      <h3 className="mb-2 text-sm font-semibold text-foreground">{title}</h3>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
              <th className="py-1 pr-3 font-medium">Post</th>
              <th className="py-1 pr-3 font-medium">Format</th>
              <th className="py-1 pr-3 text-right font-medium">Views</th>
              <th className="py-1 pr-3 text-right font-medium">Engagement rate</th>
              <th className="py-1 pr-3 font-medium" />
            </tr>
          </thead>
          <tbody>
            {posts.map((post) => (
              <tr key={post.targetId} className="border-t border-border">
                <td className="max-w-xs py-1.5 pr-3">
                  <div className="flex items-center gap-2">
                    <PlatformIcon platform={post.platform as PublishPlatform} />
                    <div className="min-w-0">
                      <PostTitle post={post} href={hrefFor(post)} />
                      {post.captionExcerpt && (
                        <p className="truncate text-xs text-muted-foreground" title={post.captionExcerpt}>
                          {post.captionExcerpt}
                        </p>
                      )}
                    </div>
                  </div>
                </td>
                <td className="py-1.5 pr-3 text-muted-foreground">{post.format ? humanizeId(post.format) : '—'}</td>
                <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">{compactCount(post.views)}</td>
                <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">
                  {engagementRateLabel(post.engagementRate)}
                </td>
                <td className="py-1.5 pr-3 text-right">
                  {post.permalink && (
                    <a
                      href={post.permalink}
                      target="_blank"
                      rel="noreferrer"
                      aria-label="Open on the platform"
                      className="text-muted-foreground hover:text-foreground"
                    >
                      <ExternalLinkIcon className="h-3.5 w-3.5" />
                    </a>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
