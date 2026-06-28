'use client'

// COND-22: legacy redirect. The Work Item detail view now lives at the workflow-scoped, human-readable
// /work/{slug}/{displayId} route. External UUID links (e.g. from Discord) still land here; we resolve the
// Work Item by UUID via the canonical /api/v2 endpoint and replace the URL with its workflow + displayId.

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet } from '@/lib/api'

export const dynamic = 'force-dynamic'

interface WorkItemResolved {
  id: string
  workflow?: string
  displayId: string
}

export default function LegacyIssueRedirectPage() {
  const { projectId, issueId } = useParams<{ projectId: string; issueId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()

  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!projectId || !issueId || !accessToken) return
    let cancelled = false
    ;(async () => {
      try {
        const resolved = await apiGet<WorkItemResolved>(
          `/api/v2/projects/${projectId}/work-items/${issueId}`,
          accessToken
        )
        if (cancelled) return
        const slug = resolved.workflow ?? 'ENGINEERING'
        router.replace(`/app/projects/${projectId}/work/${slug}/${resolved.displayId}`)
      } catch {
        if (!cancelled) setError('Work Item not found.')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [projectId, issueId, accessToken, router])

  if (error) {
    return (
      <div className="flex items-center justify-center h-64 text-destructive">{error}</div>
    )
  }

  return (
    <div className="flex items-center justify-center h-64 text-muted-foreground">Redirecting…</div>
  )
}
