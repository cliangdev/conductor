'use client'

// "Posts using this": the Posts that carry a rendered frame of this Creative's family, on the Creative
// editor. Read-only; each row opens the Post. Waits for the workflow nav (it needs the publishing workflow's
// slug to ask the server for only that workflow's posts, and its list path to link rows), then makes one
// request; editing or deleting a Creative shows what it would touch first.
//
// The "Used in N posts" count on a Creative tile counts distinct Work Items that carry one of the family's
// rendered frames, in any workflow; in practice only Posts carry them, so it matches this list.

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { apiErrorMessage, apiGet } from '@/lib/api'
import { findPostsNavEntry, useSidebarWorkNav, workItemListPath } from '@/lib/workflows'

interface PostRow {
  id: string
  displayId: string
  title: string
  status: string
  statusLabel?: string
  workflow?: string
}

export function PostsUsingCreativePanel({
  projectId,
  creativeId,
  token,
}: {
  projectId: string
  creativeId: string
  token: string
}) {
  const [posts, setPosts] = useState<PostRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { entries, loading: navLoading } = useSidebarWorkNav(projectId, token)
  const postsEntry = findPostsNavEntry(entries)
  const postsHref = postsEntry ? workItemListPath(projectId, postsEntry.area, postsEntry.noun) : null
  const slug = postsEntry?.slug ?? null
  // No token, or a nav that has settled with no publishing workflow, means there is nothing to ask for: say
  // so rather than wait on a skeleton that will never resolve.
  const unavailable = !token || (!navLoading && !slug)

  useEffect(() => {
    if (unavailable || navLoading || !slug) return
    let cancelled = false
    // A different Creative must not show the previous one's posts, or its error, while loading.
    setPosts(null)
    setError(null)
    apiGet<PostRow[]>(
      `/api/v2/projects/${projectId}/work-items?creativeId=${encodeURIComponent(creativeId)}&workflow=${encodeURIComponent(slug)}`,
      token,
    )
      .then((rows) => !cancelled && setPosts(rows))
      .catch((err) => {
        if (cancelled) return
        setPosts([])
        setError(apiErrorMessage(err, 'Could not load the posts using this creative'))
      })
    return () => {
      cancelled = true
    }
  }, [projectId, creativeId, token, slug, navLoading, unavailable])

  const shown = unavailable ? [] : navLoading ? null : posts

  return (
    <Card data-testid="posts-using-creative">
      <CardHeader>
        <h3 className="text-sm font-semibold text-foreground">Posts using this</h3>
      </CardHeader>
      <CardContent>
        {shown === null ? (
          <Skeleton className="h-8 w-full" />
        ) : error ? (
          <p className="text-sm text-destructive">{error}</p>
        ) : shown.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {unavailable ? 'Posts using this creative cannot be listed right now.' : 'No posts use this creative yet.'}
          </p>
        ) : (
          <ul className="divide-y divide-border">
            {shown.map((p) => (
              <li key={p.id} className="flex items-center gap-3 py-2 text-sm">
                <span className="w-16 shrink-0 font-mono text-xs text-muted-foreground">{p.displayId}</span>
                {postsHref ? (
                  <Link prefetch={false} href={`${postsHref}/${p.displayId}`} className="min-w-0 flex-1 truncate text-foreground hover:underline">
                    {p.title}
                  </Link>
                ) : (
                  <span className="min-w-0 flex-1 truncate text-foreground">{p.title}</span>
                )}
                <span className="shrink-0 text-xs text-muted-foreground">{p.statusLabel ?? p.status}</span>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
