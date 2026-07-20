'use client'

import { Suspense, useEffect } from 'react'
import { useParams, useRouter, useSearchParams } from 'next/navigation'

export const dynamic = 'force-dynamic'

/** The standalone sources browse now lives at the Activity page's Inbox tab (see
 *  src/components/knowledge/KnowledgeInbox.tsx) — this route just redirects there, preserving
 *  whatever `?status=`/`?domain=` filter the caller linked in with. */
function KnowledgeSourcesRedirectContent() {
  const { projectId } = useParams<{ projectId: string }>()
  const router = useRouter()
  const searchParams = useSearchParams()

  useEffect(() => {
    const sp = new URLSearchParams(searchParams.toString())
    sp.set('tab', 'inbox')
    router.replace(`/app/projects/${projectId}/knowledge/activity?${sp.toString()}`)
  }, [projectId, router, searchParams])

  return null
}

export default function KnowledgeSourcesPage() {
  return (
    <Suspense fallback={null}>
      <KnowledgeSourcesRedirectContent />
    </Suspense>
  )
}
