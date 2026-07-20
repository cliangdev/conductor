'use client'

import { Suspense, useEffect, useState } from 'react'
import { useParams, usePathname, useRouter, useSearchParams } from 'next/navigation'
import { HomeIcon, HistoryIcon, RotateCwIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { KnowledgeSearch } from '@/components/knowledge/KnowledgeSearch'
import { KnowledgePageTree } from '@/components/knowledge/KnowledgePageTree'
import { KnowledgeRailFooter } from '@/components/knowledge/KnowledgeRailFooter'
import { Skeleton } from '@/components/ui/skeleton'
import { Alert } from '@/components/ui/alert'
import { getKnowledgeIndex } from '@/lib/knowledge-api'
import {
  filterContentPages,
  groupKnowledgePages,
  parseKnowledgeIndexPages,
  type KnowledgeTreeSection,
} from '@/lib/knowledgeTree'
import { cn } from '@/lib/utils'

function RailSkeleton() {
  return (
    <div className="px-2 py-2 space-y-1.5" aria-hidden="true">
      {[80, 60, 70].map((w) => (
        <Skeleton key={w} className="h-6" style={{ width: `${w}%` }} />
      ))}
    </div>
  )
}

function KnowledgeRail() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()

  const [sections, setSections] = useState<KnowledgeTreeSection[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)
  const [retryCount, setRetryCount] = useState(0)

  useEffect(() => {
    if (!accessToken) return
    let cancelled = false
    setLoading(true)
    setError(false)
    getKnowledgeIndex(projectId, accessToken)
      .then((page) => {
        if (cancelled) return
        setSections(groupKnowledgePages(filterContentPages(parseKnowledgeIndexPages(page.content ?? ''))))
      })
      .catch(() => {
        if (!cancelled) setError(true)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [accessToken, projectId, retryCount])

  function goToPage(path: string) {
    router.push(`/app/projects/${projectId}/knowledge/page?path=${encodeURIComponent(path)}`)
  }

  const onIndex = pathname === `/app/projects/${projectId}/knowledge`
  const onActivity = pathname === `/app/projects/${projectId}/knowledge/activity`
  const onPageRoute = pathname === `/app/projects/${projectId}/knowledge/page`
  const activePath = onPageRoute ? searchParams.get('path') ?? '' : ''

  return (
    <>
      {accessToken && <KnowledgeSearch projectId={projectId} token={accessToken} onResultSelect={goToPage} />}

      <div className="px-1 py-1 space-y-0.5">
        <button
          onClick={() => router.push(`/app/projects/${projectId}/knowledge`)}
          aria-current={onIndex ? 'page' : undefined}
          className={cn(
            'w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-sm text-left transition-colors',
            onIndex
              ? 'bg-sidebar-active text-sidebar-active-text font-medium'
              : 'text-foreground hover:bg-sidebar-hover'
          )}
        >
          <HomeIcon className="h-3.5 w-3.5 shrink-0 opacity-70" />
          Home
        </button>
        <button
          onClick={() => router.push(`/app/projects/${projectId}/knowledge/activity`)}
          aria-current={onActivity ? 'page' : undefined}
          className={cn(
            'w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-sm text-left transition-colors',
            onActivity
              ? 'bg-sidebar-active text-sidebar-active-text font-medium'
              : 'text-foreground hover:bg-sidebar-hover'
          )}
        >
          <HistoryIcon className="h-3.5 w-3.5 shrink-0 opacity-70" />
          Activity
        </button>
      </div>

      <div className="border-t border-sidebar-border mt-1 pt-1">
        {error ? (
          <div className="px-2 py-2 space-y-2">
            <Alert variant="destructive" className="text-xs">
              Couldn&apos;t load knowledge pages.
            </Alert>
            <button
              onClick={() => setRetryCount((n) => n + 1)}
              className="w-full flex items-center justify-center gap-1.5 px-2 py-1.5 rounded-md text-xs font-medium text-foreground hover:bg-sidebar-hover transition-colors border border-border"
            >
              <RotateCwIcon className="h-3 w-3" />
              Retry
            </button>
          </div>
        ) : loading ? (
          <RailSkeleton />
        ) : (
          <KnowledgePageTree
            projectId={projectId}
            sections={sections}
            activePath={activePath}
            onNavigate={goToPage}
          />
        )}
      </div>
    </>
  )
}

export default function KnowledgeLayout({ children }: { children: React.ReactNode }) {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()

  return (
    <div className="flex h-full">
      {/* Left rail: search + page tree + fixed shortcuts, footer pinned to the bottom */}
      <div className="w-56 shrink-0 border-r border-border bg-sidebar-bg flex flex-col h-full">
        <div className="flex-1 overflow-y-auto">
          <Suspense fallback={<RailSkeleton />}>
            <KnowledgeRail />
          </Suspense>
        </div>
        {accessToken && <KnowledgeRailFooter projectId={projectId} token={accessToken} />}
      </div>

      {/* Right panel: page content */}
      <div className="flex-1 overflow-hidden">{children}</div>
    </div>
  )
}
