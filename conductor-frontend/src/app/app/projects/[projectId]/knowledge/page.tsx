'use client'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { LibraryIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { useToast } from '@/components/ui/toast'
import { KnowledgePageSkeleton } from '@/components/knowledge/KnowledgePageSkeleton'
import { KnowledgeSetupChecklist } from '@/components/knowledge/KnowledgeSetupChecklist'
import { KnowledgePipelineStrip } from '@/components/knowledge/KnowledgePipelineStrip'
import { Alert } from '@/components/ui/alert'
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
    return <KnowledgePageSkeleton fullHeight />
  }

  if (error) {
    return (
      <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        <Alert variant="destructive">{error}</Alert>
      </div>
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
            {accessToken && (
              <KnowledgeSetupChecklist
                projectId={projectId}
                token={accessToken}
                onEnable={handleEnable}
                enabling={enabling}
              />
            )}
          </>
        ) : (
          <p className="text-xs max-w-xs">Ask a workspace admin to enable Knowledge for this workspace.</p>
        )}
      </div>
    )
  }

  return (
    <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
      <div className="max-w-[45rem] mx-auto space-y-4">
        {accessToken && <KnowledgePipelineStrip projectId={projectId} token={accessToken} />}
        <MarkdownRenderer content={page!.content ?? ''} onWikiLink={handleWikiLink} basePath="" />
      </div>
    </div>
  )
}
