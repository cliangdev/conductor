'use client'

// A reviewer's "Fix text" on a Post: opens the slide-text editor for the Creative the Post's media came
// from, scoped to this Post so the fix stays in its review round. Offered only when that Creative is a
// slideshow with a stored spec; renders nothing otherwise.

import { useCallback, useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { SlideTextEditor } from '@/components/marketing/creatives/SlideTextEditor'
import { hasSquareSlides, SQUARE_SLIDES_NOTE } from '@/components/marketing/creatives/slideText'
import { getCreative, type Creative } from '@/components/marketing/creatives/types'

export function FixTextAction({
  projectId,
  token,
  creativeId,
  postId,
  onChanged,
}: {
  projectId: string
  token: string
  creativeId: string
  postId: string
  /** The edit applied (or the editor closed after one did): the Post's media and activity have moved. */
  onChanged: () => void | Promise<void>
}) {
  const [creative, setCreative] = useState<Creative | null>(null)
  const [open, setOpen] = useState(false)
  const applied = useRef(false)

  const load = useCallback(async () => {
    const c = await getCreative(projectId, creativeId, token)
    setCreative(c)
  }, [projectId, creativeId, token])

  useEffect(() => {
    load().catch(() => setCreative(null))
  }, [load])

  if (!creative || creative.kind !== 'CAROUSEL' || !creative.slideshowSpec) return null
  // The server refuses 1x1 slides (422 slideTextEditUnsupportedRatio): say so instead of offering a dead end.
  if (hasSquareSlides(creative.slides)) {
    return (
      <span className="text-xs text-muted-foreground" data-testid="fix-text-unavailable">
        {SQUARE_SLIDES_NOTE}
      </span>
    )
  }

  return (
    <>
      <Button variant="link" size="sm" className="h-auto shrink-0 p-0 text-xs" onClick={() => setOpen(true)} data-testid="fix-text">
        Fix text
      </Button>
      {open && (
        <SlideTextEditor
          projectId={projectId}
          token={token}
          creative={creative}
          postId={postId}
          onApplied={async () => {
            applied.current = true
            await load()
            await onChanged()
          }}
          onClose={() => {
            setOpen(false)
            // The Post is brought up to date a moment after the edit applies; read it once more on the way out.
            if (applied.current) {
              applied.current = false
              void onChanged()
            }
          }}
        />
      )}
    </>
  )
}
