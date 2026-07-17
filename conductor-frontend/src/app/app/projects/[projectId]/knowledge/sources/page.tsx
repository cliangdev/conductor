'use client'

import { Suspense, useEffect, useState } from 'react'
import { useParams, usePathname, useRouter, useSearchParams } from 'next/navigation'
import { InboxIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { listKnowledgeSources, type KnowledgeSourceDto, type KnowledgeSourceStatus } from '@/lib/knowledge-api'
import { apiErrorMessage } from '@/lib/api'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { timeAgo } from '@/lib/format'

export const dynamic = 'force-dynamic'

const STATUSES: KnowledgeSourceStatus[] = ['PENDING', 'PROCESSING', 'PROCESSED', 'DEAD']

function humanizeStatus(status: KnowledgeSourceStatus): string {
  return status.charAt(0) + status.slice(1).toLowerCase()
}

function isKnowledgeSourceStatus(value: string | null): value is KnowledgeSourceStatus {
  return STATUSES.includes(value as KnowledgeSourceStatus)
}

/** Read-only browse of the ingestion inbox, filtered by status (PENDING default). No actions — the
 *  pipeline (KnowledgeIngestScheduler + the librarian) owns the lifecycle, not the UI. */
function KnowledgeSourcesPageContent() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()

  const statusParam = searchParams.get('status')
  const status: KnowledgeSourceStatus = isKnowledgeSourceStatus(statusParam) ? statusParam : 'PENDING'

  const [sources, setSources] = useState<KnowledgeSourceDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!accessToken) return
    let cancelled = false
    setLoading(true)
    setError(null)
    listKnowledgeSources(projectId, accessToken, { status })
      .then((rows) => {
        if (!cancelled) setSources(rows)
      })
      .catch((err) => {
        if (!cancelled) setError(apiErrorMessage(err, 'Failed to load sources'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken, status])

  function setStatus(next: string) {
    const sp = new URLSearchParams(searchParams.toString())
    if (next === 'PENDING') sp.delete('status')
    else sp.set('status', next)
    const qs = sp.toString()
    router.replace(qs ? `${pathname}?${qs}` : pathname)
  }

  const tabItems: TabItem[] = STATUSES.map((s) => ({ value: s, label: humanizeStatus(s) }))

  return (
    <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
      <div className="max-w-[45rem] mx-auto space-y-4">
        <h1 className="text-xl font-semibold text-foreground">Inbox sources</h1>

        <Tabs items={tabItems} value={status} onValueChange={setStatus} ariaLabel="Filter sources by status" />

        {error ? (
          <Alert variant="destructive">{error}</Alert>
        ) : loading ? (
          <div className="space-y-1">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-[38px] w-full" />
            ))}
          </div>
        ) : sources.length === 0 ? (
          <EmptyState
            icon={InboxIcon}
            title="No sources"
            description={`No ${humanizeStatus(status).toLowerCase()} sources in the inbox.`}
          />
        ) : (
          <div className="border border-border rounded-[10px] divide-y divide-border">
            {sources.map((source) => (
              <div key={source.id} className="h-[38px] flex items-center gap-3 px-3 text-[13px]">
                <Badge variant="outline" className="shrink-0 font-mono text-[11px] font-normal">
                  {source.sourceType}
                </Badge>
                {source.domain && (
                  <Badge variant="secondary" className="shrink-0 text-[11px] font-normal">
                    {source.domain}
                  </Badge>
                )}
                <span className="truncate flex-1 text-foreground">
                  {source.title ?? source.sourceRef ?? '—'}
                </span>
                {source.purgedAt && (
                  <span className="shrink-0 text-foreground-subtle text-xs">purged</span>
                )}
                <span className="shrink-0 text-foreground-subtle">{timeAgo(source.receivedAt)}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

export default function KnowledgeSourcesPage() {
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
      <KnowledgeSourcesPageContent />
    </Suspense>
  )
}
