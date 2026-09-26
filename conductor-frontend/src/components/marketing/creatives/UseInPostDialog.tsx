'use client'

// COND-24 T3: "Use in Post" — attaches a SUCCEEDED render's frames to a Post, picked from the
// project's Draft Posts or started fresh. Reuses the same Work Item list fetch WorkItemListView
// uses (useDraftPosts), scanning every publish-capable Workflow rather than assuming a single one.

import { useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { PlusIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { Skeleton } from '@/components/ui/skeleton'
import { apiErrorMessage } from '@/lib/api'
import { workItemDetailPath, workItemListPath } from '@/lib/workflows'
import { PLATFORM_LABELS } from '@/components/marketing/destinations/publishState'
import type { PublishPlatform } from '@/components/marketing/destinations/types'
import { attachCreativeRender, type AttachCreativeResult } from '@/components/marketing/creatives/types'
import { useDraftPosts, type DraftPost } from '@/components/marketing/creatives/useDraftPosts'

function platformLabel(platform: string): string {
  return PLATFORM_LABELS[platform as PublishPlatform] ?? platform
}

export interface UseInPostDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  creativeId: string
  creativeDisplayId: string
  renderId: string
  token: string
}

export function UseInPostDialog({
  open,
  onOpenChange,
  projectId,
  creativeId,
  creativeDisplayId,
  renderId,
  token,
}: UseInPostDialogProps) {
  const router = useRouter()
  const { posts, postWorkflows, error } = useDraftPosts(projectId, token)
  const [attachingId, setAttachingId] = useState<string | null>(null)
  const [attachError, setAttachError] = useState<string | null>(null)
  const [result, setResult] = useState<{ post: DraftPost; data: AttachCreativeResult } | null>(null)

  function close(nextOpen: boolean) {
    if (!nextOpen) {
      setResult(null)
      setAttachError(null)
      setAttachingId(null)
    }
    onOpenChange(nextOpen)
  }

  function handleNewPost() {
    const wf = postWorkflows[0]
    if (!wf) return
    const path = `${workItemListPath(projectId, wf.area, wf.noun)}/new?creativeId=${creativeId}&renderId=${renderId}`
    close(false)
    router.push(path)
  }

  async function handlePick(post: DraftPost) {
    setAttachingId(post.id)
    setAttachError(null)
    try {
      const data = await attachCreativeRender(projectId, creativeId, { renderId, workItemId: post.id }, token)
      setResult({ post, data })
    } catch (err) {
      setAttachError(apiErrorMessage(err, 'Could not attach this render'))
    } finally {
      setAttachingId(null)
    }
  }

  return (
    <Modal
      open={open}
      onOpenChange={close}
      title="Use in Post"
      description={`Attach Creative ${creativeDisplayId}'s latest render to a Post.`}
    >
      {result ? (
        <div className="space-y-3" data-testid="use-in-post-result">
          <p className="text-sm text-foreground">
            Attached to{' '}
            <Link
              href={workItemDetailPath(
                projectId,
                result.post.area,
                result.post.noun,
                result.post.displayId ?? result.post.id,
              )}
              className="text-primary underline-offset-2 hover:underline"
            >
              {result.post.displayId ?? result.post.title}
            </Link>
            .
          </p>
          {result.data.targetsUpdated.length > 0 && (
            <div>
              <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Targets updated</p>
              <ul className="mt-1 space-y-0.5 text-sm">
                {result.data.targetsUpdated.map((t) => (
                  <li key={t.targetId}>
                    {platformLabel(t.platform)} — {t.assetIds.length} frame{t.assetIds.length === 1 ? '' : 's'}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {result.data.targetsSkipped.length > 0 && (
            <div>
              <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Targets skipped</p>
              <ul className="mt-1 space-y-0.5 text-sm text-muted-foreground">
                {result.data.targetsSkipped.map((t) => (
                  <li key={t.targetId}>
                    {platformLabel(t.platform)} — {t.reason}
                  </li>
                ))}
              </ul>
            </div>
          )}
          <div className="flex justify-end">
            <Button variant="outline" onClick={() => close(false)}>
              Done
            </Button>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          {postWorkflows.length > 0 && (
            <Button variant="outline" className="w-full justify-start" onClick={handleNewPost}>
              <PlusIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
              New Post from this creative
            </Button>
          )}

          {attachError && <Alert variant="destructive">{attachError}</Alert>}
          {error && <Alert variant="destructive">{error}</Alert>}

          {posts === null ? (
            <Skeleton className="h-24 w-full" />
          ) : posts.length === 0 ? (
            <p className="text-sm text-muted-foreground">No Draft Posts in this project yet.</p>
          ) : (
            <ul className="divide-y divide-border rounded-md border border-border">
              {posts.map((post) => (
                <li key={post.id}>
                  <button
                    type="button"
                    onClick={() => handlePick(post)}
                    disabled={attachingId !== null}
                    className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-sm hover:bg-surface-3 disabled:opacity-50"
                  >
                    <span className="truncate">
                      <span className="font-mono text-muted-foreground">{post.displayId}</span> {post.title}
                    </span>
                    {attachingId === post.id && <span className="text-xs text-muted-foreground">Attaching…</span>}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </Modal>
  )
}
