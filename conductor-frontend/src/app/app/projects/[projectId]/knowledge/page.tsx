'use client'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { useToast } from '@/components/ui/toast'
import { Button } from '@/components/ui/button'
import { KnowledgePageSkeleton } from '@/components/knowledge/KnowledgePageSkeleton'
import { ClaudeConnectionHint } from '@/components/knowledge/ClaudeConnectionHint'
import { KnowledgePipelineStrip } from '@/components/knowledge/KnowledgePipelineStrip'
import { KnowledgeDomainsPanel } from '@/components/knowledge/KnowledgeDomainsPanel'
import { Alert } from '@/components/ui/alert'
import { AgentAvatar, isAvatarColorToken, type AvatarColorToken } from '@/components/agents/AgentAvatar'
import { getKnowledgeIndex, enableKnowledge } from '@/lib/knowledge-api'
import type { KnowledgePageView } from '@/lib/knowledge-api'
import { apiErrorMessage, listAgents, type Agent } from '@/lib/api'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { cn } from '@/lib/utils'

const LIBRARIAN_SLUG = 'knowledge-librarian'
const FALLBACK_AVATAR_EMOJI = '📚'
const FALLBACK_AVATAR_COLOR: AvatarColorToken = 'violet'

// Static lookup, not an interpolated class string — Tailwind can't see a `` `ring-avatar-${color}` ``
// template at build time and would drop the class. Mirrors AgentAvatar's own AVATAR_COLOR_CLASSES.
const RING_COLOR_CLASSES: Record<AvatarColorToken, string> = {
  gray: 'ring-avatar-gray',
  blue: 'ring-avatar-blue',
  amber: 'ring-avatar-amber',
  violet: 'ring-avatar-violet',
  teal: 'ring-avatar-teal',
  green: 'ring-avatar-green',
  rose: 'ring-avatar-rose',
  slate: 'ring-avatar-slate',
}

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
  const [librarianAgent, setLibrarianAgent] = useState<Agent | null>(null)

  const isAdmin = can('workspace.manage')
  const empty = isEmptyIndex(page?.content)

  useEffect(() => {
    if (!accessToken) return
    getKnowledgeIndex(projectId, accessToken)
      .then(setPage)
      .catch((err) => setError(apiErrorMessage(err, 'Failed to load the knowledge index')))
      .finally(() => setLoading(false))
  }, [accessToken, projectId])

  // The librarian avatar only matters for the empty-state admin composition — fetched lazily once
  // we know that's what's rendering, rather than on every knowledge page visit.
  useEffect(() => {
    if (!accessToken || loading || error || !isAdmin || !empty) return
    let cancelled = false
    listAgents(projectId, accessToken)
      .then((agents) => {
        if (!cancelled) setLibrarianAgent(agents.find((a) => a.slug === LIBRARIAN_SLUG) ?? null)
      })
      .catch(() => {
        if (!cancelled) setLibrarianAgent(null)
      })
    return () => {
      cancelled = true
    }
  }, [accessToken, projectId, loading, isAdmin, empty])

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

  if (empty) {
    const avatarEmoji = librarianAgent?.avatarEmoji ?? FALLBACK_AVATAR_EMOJI
    const avatarColor = isAvatarColorToken(librarianAgent?.avatarColor)
      ? librarianAgent.avatarColor
      : FALLBACK_AVATAR_COLOR

    return (
      // Mirrors EmptyState's rhythm (icon-tile → title → description → action) — the librarian's
      // AgentAvatar stands in for the icon tile, so this is composed by hand rather than through
      // the EmptyState primitive (its icon slot assumes a lucide glyph in a bg-muted tile, not an
      // already-tinted circular avatar).
      <div className="flex flex-col items-center justify-center text-center px-4 py-12 h-full">
        <div className={cn('rounded-full p-1 ring-2', RING_COLOR_CLASSES[avatarColor])}>
          <AgentAvatar emoji={avatarEmoji} color={avatarColor} size="lg" />
        </div>
        <h2 className="text-lg font-semibold text-foreground mt-6 mb-2">The knowledge base is empty</h2>
        {isAdmin ? (
          <>
            <p className="text-sm text-muted-foreground max-w-sm mb-6">
              Enabling Knowledge provisions the librarian and bootstrap workflows that keep this
              workspace&apos;s wiki up to date.
            </p>
            <Button size="sm" onClick={handleEnable} disabled={enabling}>
              {enabling ? 'Enabling…' : 'Enable Knowledge'}
            </Button>
            {accessToken && (
              <div className="mt-4 w-full max-w-sm">
                <ClaudeConnectionHint projectId={projectId} token={accessToken} />
              </div>
            )}
          </>
        ) : (
          <p className="text-sm text-muted-foreground max-w-sm">
            Ask a workspace admin to enable Knowledge for this workspace.
          </p>
        )}
      </div>
    )
  }

  return (
    <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
      <div className="max-w-[45rem] mx-auto space-y-4">
        {accessToken && <KnowledgePipelineStrip projectId={projectId} token={accessToken} />}
        {accessToken && <KnowledgeDomainsPanel projectId={projectId} token={accessToken} />}
        <MarkdownRenderer content={page!.content ?? ''} onWikiLink={handleWikiLink} basePath="" />
      </div>
    </div>
  )
}
