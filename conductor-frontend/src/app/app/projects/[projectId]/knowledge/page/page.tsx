'use client'

import { Suspense, useEffect, useState } from 'react'
import { useParams, useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { History } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { getKnowledgePage, listKnowledgeRevisions } from '@/lib/knowledge-api'
import type { KnowledgePageRevisionView, KnowledgePageView } from '@/lib/knowledge-api'
import { apiErrorMessage } from '@/lib/api'
import { timeAgo } from '@/lib/format'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { KnowledgeHistoryPanel } from '@/components/knowledge/KnowledgeHistoryPanel'
import { KnowledgeTypeIcon } from '@/components/knowledge/KnowledgeTypeIcon'
import { StatusBadge } from '@/components/ui/status-badge'
import { Button } from '@/components/ui/button'
import { KnowledgePageSkeleton } from '@/components/knowledge/KnowledgePageSkeleton'
import { Alert } from '@/components/ui/alert'

export const dynamic = 'force-dynamic'

function dirOf(path: string): string {
  const idx = path.lastIndexOf('/')
  return idx < 0 ? '' : path.slice(0, idx)
}

function KnowledgePageContent() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const searchParams = useSearchParams()
  const path = searchParams.get('path') ?? ''

  const [page, setPage] = useState<KnowledgePageView | null>(null)
  const [latestRevision, setLatestRevision] = useState<KnowledgePageRevisionView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [showHistory, setShowHistory] = useState(false)

  useEffect(() => {
    if (!accessToken || !path) return
    let cancelled = false
    setLoading(true)
    setNotFound(false)
    setError(null)
    setLatestRevision(null)
    getKnowledgePage(projectId, path, accessToken)
      .then((result) => {
        if (cancelled) return
        if (!result) {
          setNotFound(true)
          return
        }
        setPage(result)
      })
      .catch((err) => {
        if (!cancelled) setError(apiErrorMessage(err, 'Failed to load page'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    // Best-effort — the revised-at line is a nice-to-have, not worth failing the page over.
    listKnowledgeRevisions(projectId, path, accessToken)
      .then((revs) => {
        if (!cancelled) setLatestRevision(revs[0] ?? null)
      })
      .catch(() => {
        if (!cancelled) setLatestRevision(null)
      })
    return () => {
      cancelled = true
    }
  }, [accessToken, projectId, path])

  function handleWikiLink(target: string) {
    router.push(`/app/projects/${projectId}/knowledge/page?path=${encodeURIComponent(target)}`)
  }

  if (!path) {
    return (
      <div className="flex items-center justify-center h-64 text-muted-foreground">
        No page specified.
      </div>
    )
  }

  if (loading) {
    return <KnowledgePageSkeleton />
  }

  if (error) {
    return (
      <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        <Alert variant="destructive">{error}</Alert>
      </div>
    )
  }

  if (notFound || !page) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-2 text-muted-foreground">
        <p className="text-sm">This page doesn&apos;t exist.</p>
        <Link href={`/app/projects/${projectId}/knowledge`} className="text-sm text-primary hover:underline">
          Back to knowledge index
        </Link>
      </div>
    )
  }

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="border-b border-border bg-background px-4 sm:px-6 lg:px-8 py-4 shrink-0">
        <div className="max-w-[45rem] mx-auto">
          <p className="text-xs text-muted-foreground truncate mb-2">{page.path}</p>
          <div className="flex items-start gap-3">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-muted text-muted-foreground">
              <KnowledgeTypeIcon type={page.type} className="h-4.5 w-4.5" />
            </span>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <h1 className="text-xl font-[650] tracking-[-0.015em] text-foreground truncate">
                  {page.title ?? page.path}
                </h1>
                <StatusBadge status={page.type} />
              </div>
              <p className="text-xs text-muted-foreground mt-1">
                Maintained by Librarian
                {latestRevision && ` · revised ${timeAgo(latestRevision.createdAt)}`}
              </p>
            </div>
            <Button variant="outline" size="sm" onClick={() => setShowHistory(true)} className="shrink-0">
              <History className="h-3.5 w-3.5 mr-1.5" />
              History
            </Button>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 relative overflow-hidden">
        <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
          <div className="max-w-[45rem] mx-auto">
            {page.content ? (
              <MarkdownRenderer content={page.content} onWikiLink={handleWikiLink} basePath={dirOf(page.path)} />
            ) : (
              <p className="text-muted-foreground text-sm">No content yet.</p>
            )}
          </div>
        </div>

        {showHistory && (
          <KnowledgeHistoryPanel
            projectId={projectId}
            path={page.path}
            token={accessToken!}
            onClose={() => setShowHistory(false)}
          />
        )}
      </div>
    </div>
  )
}

export default function KnowledgePageRoute() {
  return (
    <Suspense fallback={<KnowledgePageSkeleton lines={2} />}>
      <KnowledgePageContent />
    </Suspense>
  )
}
