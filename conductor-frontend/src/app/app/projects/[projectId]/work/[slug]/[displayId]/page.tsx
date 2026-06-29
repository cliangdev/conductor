'use client'

// Back-compat redirect shim. The Work Item detail now lives at the human-readable
// /{area}/{nouns}/{displayId} route. Briefly-live /work/{slug}/{displayId} links resolve the Workflow by
// slug (for its area + noun) and replace the URL with the new shape, preserving the displayId.

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { fetchWorkflowView, workItemDetailPath } from '@/lib/workflows'

export const dynamic = 'force-dynamic'

export default function LegacyWorkSlugDetailRedirectPage() {
  const { projectId, slug, displayId } = useParams<{
    projectId: string
    slug: string
    displayId: string
  }>()
  const { accessToken } = useAuth()
  const router = useRouter()

  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!projectId || !slug || !displayId || !accessToken) return
    let cancelled = false
    ;(async () => {
      try {
        const view = await fetchWorkflowView(projectId, slug, accessToken)
        if (cancelled) return
        router.replace(workItemDetailPath(projectId, view.area ?? slug, view.noun, displayId))
      } catch {
        if (!cancelled) setError('Workflow not found.')
      }
    })()
    return () => {
      cancelled = true
    }
  }, [projectId, slug, displayId, accessToken, router])

  if (error) {
    return <div className="flex items-center justify-center h-64 text-destructive">{error}</div>
  }

  return (
    <div className="flex items-center justify-center h-64 text-muted-foreground">
      Redirecting {displayId}…
    </div>
  )
}
