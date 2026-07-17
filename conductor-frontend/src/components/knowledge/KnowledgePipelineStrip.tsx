'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowRightIcon } from 'lucide-react'
import { getKnowledgeSourceCounts, type KnowledgeSourceCounts } from '@/lib/knowledge-api'
import { listAgents } from '@/lib/api'
import { listWorkflows, listWorkflowRuns } from '@/lib/workflows'
import type { WorkflowRunDto } from '@/types/workflow'
import { StatusBadge } from '@/components/ui/status-badge'
import { Skeleton } from '@/components/ui/skeleton'
import { timeAgo } from '@/lib/format'

// Matches KnowledgeWorkflowProvisioner's reserved workflow name / agent slug on the backend.
const LIBRARIAN_WORKFLOW_NAME = 'knowledge-librarian'
const LIBRARIAN_AGENT_SLUG = 'knowledge-librarian'

interface StripData {
  counts: KnowledgeSourceCounts
  lastRun: WorkflowRunDto | null
  librarianAgentId: string | null
}

/**
 * Auxiliary summary row for the Knowledge index page: inbox counts, the librarian's last run, and a
 * cross-link to the librarian agent. Never blocks or breaks the wiki page it sits above — a fetch
 * failure renders nothing rather than an error, and the run/agent lookups are independently
 * best-effort (a missing workflow or agent just omits that segment).
 */
export function KnowledgePipelineStrip({ projectId, token }: { projectId: string; token: string }) {
  const [loading, setLoading] = useState(true)
  const [data, setData] = useState<StripData | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)

    async function load(): Promise<StripData> {
      const counts = await getKnowledgeSourceCounts(projectId, token)

      let lastRun: WorkflowRunDto | null = null
      try {
        const workflows = await listWorkflows(projectId, token)
        const librarian = workflows.find((w) => w.name === LIBRARIAN_WORKFLOW_NAME)
        if (librarian) {
          const runs = await listWorkflowRuns(projectId, librarian.id, token, { page: 0, size: 1 })
          lastRun = runs[0] ?? null
        }
      } catch {
        // Run history is a nice-to-have on this strip — omit the segment, not the whole strip.
      }

      let librarianAgentId: string | null = null
      try {
        const agents = await listAgents(projectId, token)
        librarianAgentId = agents.find((a) => a.slug === LIBRARIAN_AGENT_SLUG)?.id ?? null
      } catch {
        librarianAgentId = null
      }

      return { counts, lastRun, librarianAgentId }
    }

    load()
      .then((result) => {
        if (!cancelled) setData(result)
      })
      .catch(() => {
        if (!cancelled) setData(null)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [projectId, token])

  if (loading) return <Skeleton className="h-10 w-full rounded-[10px]" />
  if (!data) return null

  const { counts, lastRun, librarianAgentId } = data

  return (
    <div className="flex items-center gap-4 flex-wrap rounded-[10px] border border-border bg-surface px-4 py-2.5 text-[13px]">
      <Link
        href={`/app/projects/${projectId}/knowledge/sources?status=PENDING`}
        className="text-foreground-subtle hover:text-foreground hover:underline"
      >
        {counts.pending} pending
      </Link>

      {counts.dead > 0 && (
        <Link href={`/app/projects/${projectId}/knowledge/sources?status=DEAD`}>
          <StatusBadge status="failed" label={`${counts.dead} dead`} />
        </Link>
      )}

      {lastRun && (
        <span className="flex items-center gap-1.5">
          <span className="text-foreground-subtle">Last run</span>
          <StatusBadge status={lastRun.status} />
          <span className="text-foreground-subtle">{timeAgo(lastRun.startedAt)}</span>
        </span>
      )}

      {librarianAgentId && (
        <Link
          href={`/app/projects/${projectId}/agents/${librarianAgentId}/overview`}
          className="ml-auto flex items-center gap-1 text-primary hover:underline shrink-0"
        >
          Librarian
          <ArrowRightIcon className="h-3 w-3" />
        </Link>
      )}
    </div>
  )
}
