'use client'

// "Posts using this": the Posts that carry a rendered frame of this Creative, on the Creative editor.
// Read-only; each row opens the Post. Reads the Posts list filtered by creativeId (one request), so
// editing or deleting a Creative shows what it would touch first.

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
  const { entries } = useSidebarWorkNav(projectId, token)
  const postsEntry = findPostsNavEntry(entries)
  const postsHref = postsEntry ? workItemListPath(projectId, postsEntry.area, postsEntry.noun) : null

  useEffect(() => {
    let cancelled = false
    apiGet<PostRow[]>(`/api/v2/projects/${projectId}/work-items?creativeId=${encodeURIComponent(creativeId)}`, token)
      .then((rows) => !cancelled && setPosts(rows))
      .catch((err) => {
        if (cancelled) return
        setPosts([])
        setError(apiErrorMessage(err, 'Could not load the posts using this creative'))
      })
    return () => {
      cancelled = true
    }
  }, [projectId, creativeId, token])

  return (
    <Card data-testid="posts-using-creative">
      <CardHeader>
        <h3 className="text-sm font-semibold text-foreground">Posts using this</h3>
      </CardHeader>
      <CardContent>
        {posts === null ? (
          <Skeleton className="h-8 w-full" />
        ) : error ? (
          <p className="text-sm text-destructive">{error}</p>
        ) : posts.length === 0 ? (
          <p className="text-sm text-muted-foreground">No posts use this creative yet.</p>
        ) : (
          <ul className="divide-y divide-border">
            {posts.map((p) => (
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
