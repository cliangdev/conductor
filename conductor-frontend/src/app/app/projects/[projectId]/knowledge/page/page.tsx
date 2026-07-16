'use client'

import { Suspense, useEffect, useState } from 'react'
import { useParams, useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { ChevronLeft, History } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { getKnowledgePage } from '@/lib/knowledge-api'
import type { KnowledgePageView } from '@/lib/knowledge-api'
import { apiErrorMessage } from '@/lib/api'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { KnowledgeHistoryPanel } from '@/components/knowledge/KnowledgeHistoryPanel'
import { Badge } from '@/components/ui/badge'
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
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [showHistory, setShowHistory] = useState(false)

  useEffect(() => {
    if (!accessToken || !path) return
    setLoading(true)
    setNotFound(false)
    setError(null)
    getKnowledgePage(projectId, path, accessToken)
      .then((result) => {
        if (!result) {
          setNotFound(true)
          return
        }
        setPage(result)
      })
      .catch((err) => setError(apiErrorMessage(err, 'Failed to load page')))
      .finally(() => setLoading(false))
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
        <div className="max-w-4xl mx-auto flex items-center gap-3">
          <Link
            href={`/app/projects/${projectId}/knowledge`}
            className="shrink-0 text-muted-foreground hover:text-foreground transition-colors"
            title="Back to knowledge index"
          >
            <ChevronLeft className="h-5 w-5" />
          </Link>
          <h1 className="text-xl sm:text-2xl font-semibold text-foreground flex-1 min-w-0 truncate">
            {page.title ?? page.path}
          </h1>
          <Badge variant="outline" className="shrink-0">{page.type}</Badge>
          <Button variant="outline" size="sm" onClick={() => setShowHistory(true)}>
            <History className="h-3.5 w-3.5 mr-1.5" />
            History
          </Button>
        </div>
        <p className="max-w-4xl mx-auto mt-1 pl-8 text-xs text-muted-foreground truncate">{page.path}</p>
      </div>

      {/* Content */}
      <div className="flex-1 relative overflow-hidden">
        <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
          <div className="max-w-4xl mx-auto">
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
