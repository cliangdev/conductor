'use client'

// COND-24 T3: "From a creative" — the Post media strip's other way to get media onto a Post, next to
// uploading a file directly. Lists Creatives that have a SUCCEEDED render (the only ones that have
// anything to attach) and attaches the picked one's latest render to this Post.

import { useEffect, useState } from 'react'
import { Alert } from '@/components/ui/alert'
import { Modal } from '@/components/ui/modal'
import { Skeleton } from '@/components/ui/skeleton'
import { apiErrorMessage } from '@/lib/api'
import { toastSuccess } from '@/components/ui/toast'
import { attachCreativeRender, listCreatives, type Creative } from '@/components/marketing/creatives/types'

export interface AttachCreativeDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  workItemId: string
  token: string
  /** Refetch the Post's assets so the newly attached media appears. */
  onAttached: () => void | Promise<void>
}

export function AttachCreativeDialog({
  open,
  onOpenChange,
  projectId,
  workItemId,
  token,
  onAttached,
}: AttachCreativeDialogProps) {
  const [creatives, setCreatives] = useState<Creative[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [attachingId, setAttachingId] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setError(null)
    listCreatives(projectId, token)
      .then((rows) => setCreatives(rows.filter((c) => c.latestRenderId)))
      .catch((err) => {
        setCreatives([])
        setError(apiErrorMessage(err, 'Could not load Creatives'))
      })
  }, [open, projectId, token])

  async function handlePick(creativeItem: Creative) {
    if (!creativeItem.latestRenderId) return
    setAttachingId(creativeItem.id)
    setError(null)
    try {
      const result = await attachCreativeRender(
        projectId,
        creativeItem.id,
        { renderId: creativeItem.latestRenderId, workItemId },
        token,
      )
      await onAttached()
      toastSuccess(
        result.targetsSkipped.length > 0
          ? `Attached Creative ${creativeItem.displayId} — ${result.targetsSkipped.length} destination${result.targetsSkipped.length === 1 ? '' : 's'} kept its own media`
          : `Attached Creative ${creativeItem.displayId}`,
      )
      onOpenChange(false)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not attach this Creative'))
    } finally {
      setAttachingId(null)
    }
  }

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="From a creative"
      description="Pick a Creative with a render — its frames become this Post's media."
    >
      <div className="space-y-3">
        {error && <Alert variant="destructive">{error}</Alert>}
        {creatives === null ? (
          <Skeleton className="h-24 w-full" />
        ) : creatives.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No Creative has a render yet. Render one from the Creatives page, then come back here.
          </p>
        ) : (
          <ul className="grid grid-cols-3 gap-2" data-testid="attach-creative-list">
            {creatives.map((c) => (
              <li key={c.id}>
                <button
                  type="button"
                  onClick={() => void handlePick(c)}
                  disabled={attachingId !== null}
                  className="flex w-full flex-col overflow-hidden rounded-md border border-border text-left hover:border-border-strong disabled:opacity-50"
                >
                  <span className="relative aspect-[4/5] w-full bg-surface-3">
                    {c.latestRenderThumbnailUrl && (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={c.latestRenderThumbnailUrl} alt="" className="h-full w-full object-cover" />
                    )}
                  </span>
                  <span className="truncate px-1.5 py-1 font-mono text-xs text-muted-foreground">
                    {attachingId === c.id ? 'Attaching…' : c.displayId}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </Modal>
  )
}
