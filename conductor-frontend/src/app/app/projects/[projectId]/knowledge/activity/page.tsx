'use client'

import { Suspense, useCallback, useEffect, useState } from 'react'
import { useParams, usePathname, useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { HistoryIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { getKnowledgePages, getKnowledgeSourceCounts, KNOWLEDGE_LIBRARIAN_SLUG } from '@/lib/knowledge-api'
import { listWorkflows, listWorkflowRuns } from '@/lib/workflows'
import type { WorkflowRunDto } from '@/types/workflow'
import { apiErrorMessage } from '@/lib/api'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { Alert } from '@/components/ui/alert'
import { Card } from '@/components/ui/card'
import { StatusBadge } from '@/components/ui/status-badge'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { KnowledgeInbox } from '@/components/knowledge/KnowledgeInbox'
import { KnowledgeAttentionBanner } from '@/components/knowledge/KnowledgeAttentionBanner'
import { timeAgo, formatElapsed } from '@/lib/format'

export const dynamic = 'force-dynamic'

function ChangesTab({ projectId, token }: { projectId: string; token: string }) {
  const router = useRouter()
  const [content, setContent] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    getKnowledgePages(projectId, ['log.md'], token)
      .then((pages) => {
        if (!cancelled) setContent(pages[0]?.content ?? '')
      })
      .catch((err) => {
        if (!cancelled) setError(apiErrorMessage(err, 'Failed to load page changes'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token])

  function handleWikiLink(path: string) {
    router.push(`/app/projects/${projectId}/knowledge/page?path=${encodeURIComponent(path)}`)
  }

  if (loading) {
    return (
      <div className="space-y-2">
        <Skeleton className="h-4 w-1/3" />
        <Skeleton className="h-4 w-full" />
        <Skeleton className="h-4 w-2/3" />
      </div>
    )
  }
  if (error) return <Alert variant="destructive">{error}</Alert>
  return <MarkdownRenderer content={content ?? ''} onWikiLink={handleWikiLink} basePath="" />
}

function RunsTab({ projectId, token }: { projectId: string; token: string }) {
  const [runs, setRuns] = useState<WorkflowRunDto[] | null>(null)
  const [workflowId, setWorkflowId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    listWorkflows(projectId, token)
      .then((workflows) => {
        const librarian = workflows.find((w) => w.name === KNOWLEDGE_LIBRARIAN_SLUG)
        if (!librarian) return []
        if (!cancelled) setWorkflowId(librarian.id)
        return listWorkflowRuns(projectId, librarian.id, token, { page: 0, size: 20 })
      })
      .then((rows) => {
        if (!cancelled) setRuns(rows ?? [])
      })
      .catch((err) => {
        if (!cancelled) setError(apiErrorMessage(err, 'Failed to load librarian runs'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token])

  if (loading) {
    return (
      <div className="space-y-1">
        {[0, 1, 2].map((i) => (
          <Skeleton key={i} className="h-[52px] w-full" />
        ))}
      </div>
    )
  }
  if (error) return <Alert variant="destructive">{error}</Alert>
  if (!runs || runs.length === 0) {
    return <EmptyState icon={HistoryIcon} title="No librarian runs yet." />
  }

  return (
    <Card className="divide-y divide-border">
      {runs.map((run) => {
        const row = (
          <div className="flex items-center gap-3 px-4 py-3 text-[13px]">
            <StatusBadge status={run.status} />
            <span className="text-foreground-subtle">{timeAgo(run.startedAt)}</span>
            <span className="ml-auto text-foreground-subtle">{formatElapsed(run.startedAt, run.completedAt)}</span>
          </div>
        )
        if (!workflowId) return <div key={run.id}>{row}</div>
        return (
          <Link
            key={run.id}
            href={`/app/projects/${projectId}/workflows/${workflowId}/runs/${run.id}`}
            className="block hover:bg-surface-2 transition-colors"
          >
            {row}
          </Link>
        )
      })}
    </Card>
  )
}

function KnowledgeActivityPageContent() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()

  const tab = searchParams.get('tab') ?? 'changes'
  const [deadCount, setDeadCount] = useState(0)
  // Bumped after a successful retry to remount KnowledgeInbox (its own list refetch has no other
  // external trigger) alongside the dead-count reload below.
  const [inboxRefreshKey, setInboxRefreshKey] = useState(0)

  const loadDeadCount = useCallback(() => {
    if (!accessToken) return
    getKnowledgeSourceCounts(projectId, accessToken)
      .then((counts) => setDeadCount(counts.dead))
      .catch(() => setDeadCount(0))
  }, [projectId, accessToken])

  useEffect(() => {
    loadDeadCount()
  }, [loadDeadCount])

  function handleRetried() {
    loadDeadCount()
    setInboxRefreshKey((k) => k + 1)
  }

  function setTab(next: string) {
    const sp = new URLSearchParams(searchParams.toString())
    if (next === 'changes') sp.delete('tab')
    else sp.set('tab', next)
    const qs = sp.toString()
    router.replace(qs ? `${pathname}?${qs}` : pathname)
  }

  const tabItems: TabItem[] = [
    { value: 'changes', label: 'Page changes' },
    {
      value: 'inbox',
      label: (
        <span className="inline-flex items-center gap-1.5">
          Inbox
          {deadCount > 0 && (
            <span className="inline-flex items-center justify-center h-4 min-w-4 px-1 rounded-full bg-status-failed/10 text-status-failed text-[10px] font-medium">
              {deadCount}
            </span>
          )}
        </span>
      ),
    },
    { value: 'runs', label: 'Runs' },
  ]

  return (
    <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
      <div className="max-w-[45rem] mx-auto space-y-4">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Activity</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Everything the librarian has read, filed, and written
          </p>
        </div>

        <Tabs items={tabItems} value={tab} onValueChange={setTab} ariaLabel="Activity views" />

        {accessToken && tab === 'changes' && <ChangesTab projectId={projectId} token={accessToken} />}
        {accessToken && tab === 'inbox' && (
          <div className="space-y-4">
            <KnowledgeAttentionBanner
              projectId={projectId}
              token={accessToken}
              deadCount={deadCount}
              onRetried={handleRetried}
            />
            <KnowledgeInbox key={inboxRefreshKey} projectId={projectId} token={accessToken} />
          </div>
        )}
        {accessToken && tab === 'runs' && <RunsTab projectId={projectId} token={accessToken} />}
      </div>
    </div>
  )
}

export default function KnowledgeActivityPage() {
  return (
    <Suspense
      fallback={
        <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
          <div className="max-w-[45rem] mx-auto space-y-2">
            <Skeleton className="h-6 w-32" />
            <Skeleton className="h-8 w-full" />
          </div>
        </div>
      }
    >
      <KnowledgeActivityPageContent />
    </Suspense>
  )
}
