'use client'

import type { ReactNode } from 'react'
import { ChevronLeftIcon, ChevronRightIcon, ListIcon } from 'lucide-react'
import { Sheet } from '@/components/ui/sheet'
import { Button } from '@/components/ui/button'
import { CommentComposer, type SubmitOptions } from './CommentComposer'
import type { TextQuoteAnchor } from './types'
import { useThreadKeys } from './RailHeader'

/** What the sheet shows: one thread, the whole filtered list, or a new comment being written. */
export type CommentSheetView =
  | { kind: 'thread' }
  | { kind: 'list' }
  | { kind: 'compose'; quote: string }

interface Props {
  /** `tablet` slides in from the right, `phone` up from the bottom. */
  mode: 'tablet' | 'phone'
  /** null = closed. */
  view: CommentSheetView | null
  total: number
  onClose: () => void
  /** Switches a thread view to the full list ("All comments"). */
  onShowAll: () => void
  /** The focused thread's card (thread view). */
  thread?: ReactNode
  /** "‹ 2 of 7 ›" over the open, placed threads; `j` / `k` use it too. */
  nav: { index: number | null; total: number; onPrev: () => void; onNext: () => void }
  /** Filters and the Whole document section, above the list (list view). */
  listHeader?: ReactNode
  /** The cards (list view). */
  list?: ReactNode
  /** Posts the new comment (compose view). */
  onSubmitCompose?: (text: string, options?: SubmitOptions) => Promise<void> | void
  /** The selection being commented on; offers "Suggest edit" in the compose view. */
  suggestFor?: TextQuoteAnchor
}

function ThreadNav({ index, total, onPrev, onNext, onShowAll }: Props['nav'] & { onShowAll: () => void }) {
  useThreadKeys(onNext, onPrev)
  return (
    <div className="mb-3 flex items-center justify-between gap-2">
      <span className="inline-flex items-center gap-0.5 text-xs tabular-nums text-muted-foreground">
        <button
          type="button"
          aria-label="Previous thread"
          disabled={total === 0}
          onClick={onPrev}
          className="rounded p-1.5 hover:bg-muted disabled:opacity-40 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ChevronLeftIcon className="h-4 w-4" />
        </button>
        <span data-testid="sheet-position">
          {index === null ? '–' : index} of {total}
        </span>
        <button
          type="button"
          aria-label="Next thread"
          disabled={total === 0}
          onClick={onNext}
          className="rounded p-1.5 hover:bg-muted disabled:opacity-40 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ChevronRightIcon className="h-4 w-4" />
        </button>
      </span>
      <Button variant="ghost" size="sm" className="h-8 px-2 text-xs" onClick={onShowAll}>
        <ListIcon className="mr-1.5 h-3.5 w-3.5" />
        All comments
      </Button>
    </div>
  )
}

/**
 * The comments on a narrow screen, shared by both document viewers: a right-hand sheet on a tablet and a
 * bottom sheet on a phone. It only lays things out; the viewer decides what the cards, filters and
 * composer do, so the same cards (and anything later added to them) appear here and in the docked rail.
 */
export function CommentSheet({
  mode,
  view,
  total,
  onClose,
  onShowAll,
  thread,
  nav,
  listHeader,
  list,
  onSubmitCompose,
  suggestFor,
}: Props) {
  const title = !view ? 'Comments' : view.kind === 'thread' ? 'Comment' : view.kind === 'list' ? `Comments · ${total}` : 'Comment on selection'

  return (
    <Sheet
      open={view !== null}
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={title}
      side={mode === 'phone' ? 'bottom' : 'right'}
      showClose
      data-sheet="comments"
      // Writing a comment: land in the box, not on the close button.
      initialFocus={
        view?.kind === 'compose'
          ? () => document.querySelector<HTMLElement>('[data-sheet="comments"] textarea')
          : undefined
      }
    >
      {view?.kind === 'thread' && (
        <>
          <ThreadNav {...nav} onShowAll={onShowAll} />
          {thread ?? <p className="text-sm text-muted-foreground">This thread is no longer here.</p>}
        </>
      )}
      {view?.kind === 'list' && (
        <div className="space-y-3">
          {listHeader}
          <div className="space-y-2" data-testid="sheet-list">
            {list}
          </div>
        </div>
      )}
      {view?.kind === 'compose' && (
        <div>
          <blockquote className="mb-3 border-l-4 border-muted pl-3">
            <p className="line-clamp-4 text-xs italic leading-relaxed text-muted-foreground">{view.quote}</p>
          </blockquote>
          <CommentComposer
            autoFocus
            onSubmit={(text, options) => onSubmitCompose?.(text, options)}
            onCancel={onClose}
            suggestFor={suggestFor}
          />
        </div>
      )}
    </Sheet>
  )
}
