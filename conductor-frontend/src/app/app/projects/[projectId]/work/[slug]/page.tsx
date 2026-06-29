'use client'

// Back-compat redirect shim. The Work Item list now lives at the human-readable /{area}/{nouns} route.
// Briefly-live /work/{slug} links resolve the Workflow by slug (for its area + noun) and replace the URL
// with the new shape.

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { fetchWorkflowView, workItemListPath } from '@/lib/workflows'

export const dynamic = 'force-dynamic'

export default function LegacyWorkSlugRedirectPage() {
  const { projectId, slug } = useParams<{ projectId: string; slug: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()

  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!projectId || !slug || !accessToken) return
    let cancelled = false
    ;(async () => {
      try {
        const view = await fetchWorkflowView(projectId, slug, accessToken)
        if (cancelled) return
        router.replace(workItemListPath(projectId, view.area ?? slug, view.noun))
      } catch {
        if (!cancelled) setError('Workflow not found.')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [projectId, slug, accessToken, router])

  if (error) {
    return <div className="flex items-center justify-center h-64 text-destructive">{error}</div>
  }

  return (
    <div className="flex items-center justify-center h-64 text-muted-foreground">Redirecting…</div>
  )
}
