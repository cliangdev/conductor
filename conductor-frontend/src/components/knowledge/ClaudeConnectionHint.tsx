'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ArrowRightIcon, InfoIcon } from 'lucide-react'
import { Skeleton } from '@/components/ui/skeleton'
import { listProviderCredentialStatuses } from '@/lib/api'

type ConnectionState = 'loading' | 'connected' | 'api-key-only' | 'not-connected'

/**
 * Admin-only guidance shown in the Knowledge empty state. Replaces the old multi-row
 * KnowledgeSetupChecklist — a project API key is no longer needed for Conductor MCP auth
 * (the backend mints a run-scoped token automatically, see docs/workflows.md), so this checks
 * Claude connectivity only, with a single `listProviderCredentialStatuses` call. Renders nothing
 * once the `claude-code` runtime is connected: guidance, never a gate — `Enable Knowledge` stays
 * clickable regardless, since the pipeline self-heals.
 */
export function ClaudeConnectionHint({ projectId, token }: { projectId: string; token: string }) {
  const [state, setState] = useState<ConnectionState>('loading')

  useEffect(() => {
    let cancelled = false
    listProviderCredentialStatuses(projectId, token)
      .then((statuses) => {
        if (cancelled) return
        const configured = Object.fromEntries(statuses.map((s) => [s.provider, s.configured]))
        if (configured['claude-code']) setState('connected')
        else if (configured['claude']) setState('api-key-only')
        else setState('not-connected')
      })
      .catch(() => {
        if (!cancelled) setState('not-connected')
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token])

  if (state === 'loading') {
    return <Skeleton className="h-9 w-full rounded-md" />
  }

  if (state === 'connected') {
    return null
  }

  const settingsHref = `/app/projects/${projectId}/settings/providers`
  const message =
    state === 'api-key-only'
      ? 'Claude is connected via API key. The bootstrap workflow additionally needs a Claude Code subscription.'
      : 'Connect Claude to power the librarian.'

  return (
    <div className="flex items-center gap-2 rounded-md border border-border bg-surface-raised px-3 py-2 text-xs text-muted-foreground">
      <InfoIcon className="h-3.5 w-3.5 shrink-0" />
      <span className="text-left">{message}</span>
      <Link
        href={settingsHref}
        className="ml-auto shrink-0 inline-flex items-center gap-1 text-primary hover:underline"
      >
        Set up
        <ArrowRightIcon className="h-3 w-3" />
      </Link>
    </div>
  )
}
