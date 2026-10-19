'use client'

// The bulk TikTok consent page body: one read of everything pending (or of one consent request),
// the signed media previews per Post, a card per Post and a progress line. The person consenting is
// the signed-in session; the link itself carries no authority.

import { useCallback, useEffect, useState } from 'react'
import { ShieldCheck } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { apiErrorMessage, apiGet } from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { fetchConsentPage, type TikTokConsentPage } from './api'
import { ConsentCard } from './ConsentCard'

function loadErrorMessage(err: unknown): string {
  const status = (err as { status?: number })?.status
  if (status === 410) return 'This link expired — ask the agent for a new one.'
  if (status === 403) return apiErrorMessage(err, 'This link was created for someone else.')
  if (status === 404) return 'This consent request could not be found.'
  return apiErrorMessage(err, 'Could not load the posts waiting for TikTok consent.')
}

export function ConsentPageView({ projectId, requestId }: { projectId: string; requestId?: string | null }) {
  const { user, accessToken } = useAuth()
  const [page, setPage] = useState<TikTokConsentPage | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [assets, setAssets] = useState<Record<string, MediaAsset[]>>({})
  const [stale, setStale] = useState<Set<string>>(new Set())

  const loadAssets = useCallback(
    (workItemIds: string[]) => {
      if (!accessToken) return
      for (const id of workItemIds) {
        apiGet<MediaAsset[]>(`/api/v2/projects/${projectId}/work-items/${id}/assets`, accessToken)
          .then((list) => setAssets((current) => ({ ...current, [id]: list })))
          // A Post whose media can't be listed just previews without it; Confirm is unaffected.
          .catch(() => setAssets((current) => ({ ...current, [id]: [] })))
      }
    },
    [projectId, accessToken]
  )

  useEffect(() => {
    if (!accessToken) return
    let cancelled = false
    fetchConsentPage(projectId, accessToken, requestId)
      .then((data) => {
        if (cancelled) return
        setPage(data)
        setError(null)
        loadAssets(data.posts.filter((p) => p.verdict !== 'VALID').map((p) => p.workItemId))
      })
      .catch((err) => {
        if (!cancelled) setError(loadErrorMessage(err))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken, requestId, loadAssets])

  const handleStale = useCallback(
    async (workItemId: string) => {
      if (!accessToken) return
      try {
        const data = await fetchConsentPage(projectId, accessToken, requestId)
        // Only this card is replaced: the others keep what the person has already chosen.
        setPage((current) => {
          if (!current) return data
          const fresh = data.posts.find((p) => p.workItemId === workItemId)
          return {
            ...current,
            creators: data.creators,
            posts: current.posts.map((p) => (p.workItemId === workItemId && fresh ? fresh : p)),
          }
        })
        loadAssets([workItemId])
        setStale((current) => new Set(current).add(workItemId))
      } catch (err) {
        setError(loadErrorMessage(err))
      }
    },
    [projectId, accessToken, requestId, loadAssets]
  )

  const handleConsented = useCallback(
    (workItemId: string, consentedAt: string | null, consentedByName: string | null) => {
      setPage((current) =>
        current
          ? {
              ...current,
              posts: current.posts.map((p) =>
                p.workItemId === workItemId
                  ? {
                      ...p,
                      verdict: 'VALID',
                      consentedAt,
                      consentedByUserId: user?.id ?? null,
                      consentedByName,
                    }
                  : p
              ),
            }
          : current
      )
      setStale((current) => {
        const next = new Set(current)
        next.delete(workItemId)
        return next
      })
    },
    [user]
  )

  if (error) return <Alert variant="destructive">{error}</Alert>

  if (loading || !page) {
    return (
      <div className="space-y-3" aria-busy="true">
        <Skeleton className="h-6 w-48" />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  if (page.posts.length === 0) {
    return (
      <EmptyState
        icon={ShieldCheck}
        title="No posts are waiting for TikTok consent"
        description="When an agent prepares posts for TikTok, they show up here for you to review and consent to."
      />
    )
  }

  const consented = page.posts.filter((p) => p.verdict === 'VALID').length

  return (
    <div className="space-y-4">
      <p className="text-sm font-medium text-foreground" aria-live="polite">
        {consented} of {page.posts.length} consented
      </p>
      <ul className="space-y-4">
        {page.posts.map((post) => (
          <ConsentCard
            key={`${post.workItemId}:${post.etag}`}
            projectId={projectId}
            token={accessToken ?? ''}
            post={post}
            creators={page.creators}
            assets={assets[post.workItemId] ?? null}
            requestId={page.requestId ?? requestId}
            currentUserId={user?.id}
            stale={stale.has(post.workItemId)}
            onConsented={handleConsented}
            onStale={(id) => void handleStale(id)}
          />
        ))}
      </ul>
    </div>
  )
}
