'use client'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { LibraryIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { useToast } from '@/components/ui/toast'
import { Button } from '@/components/ui/button'
import { getKnowledgeIndex, enableKnowledge } from '@/lib/knowledge-api'
import type { KnowledgePageView } from '@/lib/knowledge-api'
import { apiErrorMessage } from '@/lib/api'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'

export const dynamic = 'force-dynamic'

/** The generated index renders as "# Index" with no bullet links when the bundle has no pages. */
function isEmptyIndex(content: string | undefined | null): boolean {
  if (!content) return true
  return !content.includes('* [')
}

export default function KnowledgeIndexPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const { can } = usePermissions()
  const { showToast } = useToast()
  const router = useRouter()

  const [page, setPage] = useState<KnowledgePageView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [enabling, setEnabling] = useState(false)

  const isAdmin = can('workspace.manage')

  useEffect(() => {
    if (!accessToken) return
    getKnowledgeIndex(projectId, accessToken)
      .then(setPage)
      .catch((err) => setError(apiErrorMessage(err, 'Failed to load the knowledge index')))
      .finally(() => setLoading(false))
  }, [accessToken, projectId])

  function handleWikiLink(path: string) {
    router.push(`/app/projects/${projectId}/knowledge/page?path=${encodeURIComponent(path)}`)
  }

  async function handleEnable() {
    if (!accessToken) return
    setEnabling(true)
    try {
      await enableKnowledge(projectId, accessToken)
      showToast('Knowledge enabled for this workspace')
      setPage(await getKnowledgeIndex(projectId, accessToken))
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to enable Knowledge'), 'error')
    } finally {
      setEnabling(false)
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64 text-muted-foreground">Loading...</div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-64 text-destructive">Error: {error}</div>
    )
  }

  if (isEmptyIndex(page?.content)) {
    return (
      <div className="flex flex-col items-center justify-center h-full gap-3 text-muted-foreground text-center px-6">
        <LibraryIcon className="h-10 w-10 opacity-30" strokeWidth={1.5} />
        <p className="text-sm font-medium">The knowledge base is empty</p>
        {isAdmin ? (
          <>
            <p className="text-xs max-w-xs">
              Enable Knowledge, or dispatch the bootstrap workflow, to start populating this
              workspace&apos;s knowledge base.
            </p>
            <Button size="sm" onClick={handleEnable} disabled={enabling}>
              {enabling ? 'Enabling…' : 'Enable Knowledge'}
            </Button>
          </>
        ) : (
          <p className="text-xs max-w-xs">Ask a workspace admin to enable Knowledge for this workspace.</p>
        )}
      </div>
    )
  }

  return (
    <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
      <div className="max-w-4xl mx-auto">
        <MarkdownRenderer content={page!.content ?? ''} onWikiLink={handleWikiLink} basePath="" />
      </div>
    </div>
  )
}
