'use client'

import { useEffect, useState } from 'react'
import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import { InboxIcon } from 'lucide-react'
import { listKnowledgeSources, type KnowledgeSourceDto, type KnowledgeSourceStatus } from '@/lib/knowledge-api'
import { apiErrorMessage } from '@/lib/api'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { timeAgo } from '@/lib/format'

// Matches KnowledgeRailFooter/KnowledgeAttentionBanner's own poll interval (KnowledgeIngestScheduler's
// 30s tick) — an admin watching this tab needs to see a source move through PENDING/PROCESSING/DEAD
// on its own, not just at whatever moment the tab happened to load.
const SOURCES_POLL_INTERVAL_MS = 30_000

const STATUSES: KnowledgeSourceStatus[] = ['PENDING', 'PROCESSING', 'PROCESSED', 'DEAD']

const STATUS_LABELS: Record<KnowledgeSourceStatus, string> = {
  PENDING: 'Waiting',
  PROCESSING: 'Filing',
  PROCESSED: 'Filed',
  DEAD: 'Needs attention',
}

function humanizeStatus(status: KnowledgeSourceStatus): string {
  return STATUS_LABELS[status]
}

/** Empty-state copy per status — DEAD gets bespoke phrasing since "no needs attention sources"
 *  reads awkwardly as a lowercased adjective-first sentence. */
function emptyStateDescription(status: KnowledgeSourceStatus, domain: string | null): string {
  const location = domain ? `in the "${domain}" area` : 'in the inbox'
  if (status === 'DEAD') return `No sources need attention ${location}.`
  return `No ${humanizeStatus(status).toLowerCase()} sources ${location}.`
}

function isKnowledgeSourceStatus(value: string | null): value is KnowledgeSourceStatus {
  return STATUSES.includes(value as KnowledgeSourceStatus)
}

/**
 * Read-only browse of the ingestion inbox, filtered by status (PENDING default). No actions — the
 * pipeline (KnowledgeIngestScheduler + the librarian) owns the lifecycle, not the UI.
 *
 * Moved here from the standalone `/knowledge/sources` route (now a redirect) — this is the Inbox
 * tab's content on the unified Activity page. Reads/writes `?status=` and `?domain=` on the current
 * URL directly, alongside whatever `?tab=` the parent Activity page has already set.
 */
export function KnowledgeInbox({ projectId, token }: { projectId: string; token: string }) {
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()

  const statusParam = searchParams.get('status')
  const status: KnowledgeSourceStatus = isKnowledgeSourceStatus(statusParam) ? statusParam : 'PENDING'
  const domainParam = searchParams.get('domain')

  const [sources, setSources] = useState<KnowledgeSourceDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    // `background` polls never touch loading/error state — a silent poll failure just leaves the
    // list stale for one more tick rather than replacing a populated list with an error banner.
    function load(background: boolean) {
      if (!background) {
        setLoading(true)
        setError(null)
      }
      listKnowledgeSources(projectId, token, { status, domain: domainParam ?? undefined })
        .then((rows) => {
          if (!cancelled) setSources(rows)
        })
        .catch((err) => {
          if (!cancelled && !background) setError(apiErrorMessage(err, 'Failed to load sources'))
        })
        .finally(() => {
          if (!cancelled && !background) setLoading(false)
        })
    }

    load(false)
    const interval = setInterval(() => load(true), SOURCES_POLL_INTERVAL_MS)

    return () => {
      cancelled = true
      clearInterval(interval)
    }
  }, [projectId, token, status, domainParam])

  function setStatus(next: string) {
    const sp = new URLSearchParams(searchParams.toString())
    if (next === 'PENDING') sp.delete('status')
    else sp.set('status', next)
    const qs = sp.toString()
    router.replace(qs ? `${pathname}?${qs}` : pathname)
  }

  function clearDomainFilter() {
    const sp = new URLSearchParams(searchParams.toString())
    sp.delete('domain')
    const qs = sp.toString()
    router.replace(qs ? `${pathname}?${qs}` : pathname)
  }

  const tabItems: TabItem[] = STATUSES.map((s) => ({ value: s, label: humanizeStatus(s) }))

  return (
    <div className="space-y-4">
      {domainParam && (
        <div className="flex items-center gap-2 text-[13px] text-foreground-subtle">
          <span>Filtered to area:</span>
          <Badge variant="secondary" className="text-[11px] font-normal">
            {domainParam}
          </Badge>
          <button type="button" onClick={clearDomainFilter} className="hover:text-foreground hover:underline">
            Clear
          </button>
        </div>
      )}

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
        <EmptyState icon={InboxIcon} title="No sources" description={emptyStateDescription(status, domainParam)} />
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
              <span className="truncate flex-1 text-foreground">{source.title ?? source.sourceRef ?? '—'}</span>
              {source.purgedAt && <span className="shrink-0 text-foreground-subtle text-xs">purged</span>}
              <span className="shrink-0 text-foreground-subtle">{timeAgo(source.receivedAt)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
