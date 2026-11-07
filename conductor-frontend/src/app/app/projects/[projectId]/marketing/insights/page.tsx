'use client'

// "What's working" now lives on the Posts list as its What's working tab (?view=insights), the way
// Plans / Roadmap sit on the Issues list. This route only forwards old links and bookmarks, keeping
// their window / platform params.

import { Suspense, useEffect } from 'react'
import { useParams, useRouter, useSearchParams } from 'next/navigation'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuth } from '@/contexts/AuthContext'
import { findPostsNavEntry, useSidebarWorkNav, workItemListPath } from '@/lib/workflows'

export const dynamic = 'force-dynamic'

function InsightsRedirect() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const searchParams = useSearchParams()
  const { entries, loading } = useSidebarWorkNav(projectId, accessToken)
  const posts = findPostsNavEntry(entries)

  useEffect(() => {
    if (!posts && loading) return
    const base = posts
      ? workItemListPath(projectId, posts.area, posts.noun)
      : `/app/projects/${projectId}/marketing/posts`
    const sp = new URLSearchParams(searchParams.toString())
    sp.set('view', 'insights')
    router.replace(`${base}?${sp.toString()}`)
  }, [posts, loading, projectId, router, searchParams])

  return <Skeleton className="m-6 h-96 w-full" />
}

export default function MarketingInsightsRedirectPage() {
  return (
    <Suspense fallback={<Skeleton className="m-6 h-96 w-full" />}>
      <InsightsRedirect />
    </Suspense>
  )
}
