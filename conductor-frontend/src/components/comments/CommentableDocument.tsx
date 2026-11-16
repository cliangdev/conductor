'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { MessageSquare, MessageSquarePlus, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { CommentCard, PendingCommentCard } from './CommentCard'
import { CommentComposer } from './CommentComposer'
import { MarginRail, type MarginRailItem } from './MarginRail'
import { RailHeader, type RailFilter } from './RailHeader'
import { WholeDocumentSection, legacyLineNote, unplacedNote } from './WholeDocumentSection'
import { useComments, type CommentTarget } from './useComments'
import { anchorFromRange, useDocumentAnchors, type AnchorInput } from './useDocumentAnchors'
import type { Comment, PendingCommentDraft, TextQuoteAnchor } from './types'

interface Props {
  content: string
  /** Hash of `content`, sent with a new comment so the server can tell which version it was written against. */
  contentHash?: string | null
  /** What the comments belong to: a Work Item document, or a project doc. */
  target: CommentTarget
  token: string
  currentUserId: string
  currentUserName?: string
  /** Admins may delete anyone's comment. */
  isAdmin?: boolean
  /** Called after a comment change has been saved, so counts elsewhere on the page can refresh. */
  onCommentsChanged?: () => void
  onDocumentNavigate?: (filename: string) => void
  /**
   * Batch review mode (COND-22): when true, a new comment is held client-side via `onAddPendingComment`
   * instead of posted immediately. Omit for the normal immediate-post behaviour (project docs).
   */
  reviewMode?: boolean
  /** All of the Work Item's pending drafts — filtered internally to this document. */
  pendingComments?: PendingCommentDraft[]
  onAddPendingComment?: (anchor: TextQuoteAnchor, content: string, documentHash?: string) => void
  onEditPendingComment?: (localId: string, content: string) => void
  onRemovePendingComment?: (localId: string) => void
  /**
   * Opt in to interactive GFM checkboxes (see `MarkdownRenderer`). A checkbox never changes the text
   * around it, so comment anchors hold when one is toggled.
   */
  onToggleTask?: (lineNumber: number, checked: boolean) => void
  tasksReadOnly?: boolean
}

interface Spot {
  anchor: TextQuoteAnchor
  /** Where the button goes, relative to the component. */
  left: number
  top: number
  /** The selection's own left and bottom edges, so the form can open left-aligned just below it. */
  selLeft: number
  bottom: number
}

const BUTTON_WIDTH = 112
const BUTTON_HEIGHT = 32
const FORM_WIDTH = 336
/** Below this width a docked rail would squeeze the text, so the rail floats over it. */
const DOCK_MIN_WIDTH = 1024
/** A card starts a little above the first line of its words, so its header lines up with the text. */
const ANCHOR_INSET = 8

type Entry =
  | { kind: 'comment'; id: string; comment: Comment; top: number; note?: React.ReactNode }
  | { kind: 'draft'; id: string; draft: PendingCommentDraft; top: number; note?: React.ReactNode }

function draftId(localId: string): string {
  return `draft:${localId}`
}

function kindOf(c: Comment): 'QUOTE' | 'LINE' | 'DOCUMENT' {
  if (c.kind) return c.kind
  if (c.anchor) return 'QUOTE'
  return c.lineNumber ? 'LINE' : 'DOCUMENT'
}

function isResolved(c: Comment): boolean {
  return c.status ? c.status === 'RESOLVED' : !!c.resolvedAt
}

/**
 * The cards, lined up with their highlights. It owns the scroll-driven offset so that scrolling
 * re-renders only the rail, never the markdown beside it.
 */
function AlignedRail({
  items,
  activeId,
  contentRef,
  children,
}: {
  items: MarginRailItem[]
  activeId: string | null
  contentRef: React.RefObject<HTMLElement | null>
  children?: React.ReactNode
}) {
  const alignedRef = useRef<HTMLDivElement>(null)
  // Cards are laid out in the text's coordinates; this is how far the top of the rail's card area sits
  // below the top of the text, which changes as the page scrolls past the sticky rail.
  const [offset, setOffset] = useState(0)

  useEffect(() => {
    let frame: number | null = null
    const measure = () => {
      frame = null
      const aligned = alignedRef.current
      const content = contentRef.current
      if (!aligned || !content) return
      setOffset(Math.round(aligned.getBoundingClientRect().top - content.getBoundingClientRect().top))
    }
    const schedule = () => {
      if (frame === null) frame = requestAnimationFrame(measure)
    }
    measure()
    // The page scrolls inside some ancestor, and scroll events do not bubble: listen in the capture phase.
    document.addEventListener('scroll', schedule, { capture: true, passive: true })
    window.addEventListener('resize', schedule)
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule)
    if (alignedRef.current) ro?.observe(alignedRef.current)
    if (contentRef.current) ro?.observe(contentRef.current)
    return () => {
      if (frame !== null) cancelAnimationFrame(frame)
      document.removeEventListener('scroll', schedule, { capture: true })
      window.removeEventListener('resize', schedule)
      ro?.disconnect()
    }
  }, [contentRef])

  return (
    <div ref={alignedRef} className="relative min-h-0 flex-1">
      <MarginRail items={items} activeId={activeId} offset={offset} />
      {children}
    </div>
  )
}

/**
 * A markdown document with comments on selected text. The commented words stay highlighted in the page
 * and each thread is a card in a rail on the right, lined up with its highlight (docked from 1024px
 * wide; narrower, the rail floats over the page on request). Older line comments highlight the block
 * that shows their line.
 */
export function CommentableDocument({
  content,
  contentHash,
  target,
  token,
  currentUserId,
  currentUserName,
  isAdmin = false,
  onCommentsChanged,
  onDocumentNavigate,
  reviewMode = false,
  pendingComments = [],
  onAddPendingComment,
  onEditPendingComment,
  onRemovePendingComment,
  onToggleTask,
  tasksReadOnly,
}: Props) {
  const api = useComments(target, {
    token,
    currentUser: { id: currentUserId, name: currentUserName ?? 'You' },
    onChanged: onCommentsChanged,
  })
  const comments = api.comments

  const wrapperRef = useRef<HTMLDivElement>(null)
  const contentRef = useRef<HTMLDivElement>(null)
  const listRef = useRef<HTMLDivElement>(null)

  const [selection, setSelection] = useState<Spot | null>(null)
  const [composing, setComposing] = useState<Spot | null>(null)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [filter, setFilter] = useState<RailFilter>('open')
  const [editingPendingId, setEditingPendingId] = useState<string | null>(null)
  // 0 until measured (and always in tests): treated as wide enough to dock.
  const [wrapperWidth, setWrapperWidth] = useState(0)
  const docked = wrapperWidth === 0 || wrapperWidth >= DOCK_MIN_WIDTH
  // null = follow the default (docked: open, floating: closed); a boolean once the reader toggles it.
  const [railChoice, setRailChoice] = useState<boolean | null>(null)
  const railOpen = railChoice ?? docked

  useEffect(() => {
    const el = wrapperRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(([entry]) => setWrapperWidth(Math.round(entry.contentRect.width)))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const documentId = target.documentId
  const docPending = useMemo(
    () => (documentId ? pendingComments.filter((p) => p.documentId === documentId) : []),
    [pendingComments, documentId]
  )

  // ── Highlights ─────────────────────────────────────────────────────────────
  // Open threads are highlighted. A thread that was just resolved keeps its highlight while it is the
  // focused one, so it does not vanish from under the reader's cursor.
  const threads = useMemo<AnchorInput[]>(() => {
    const out: AnchorInput[] = []
    for (const c of comments) {
      if (kindOf(c) === 'DOCUMENT') continue
      if (!isResolved(c) || c.id === activeId) {
        out.push({ id: c.id, anchor: c.anchor, lineNumber: c.anchor ? undefined : c.lineNumber })
      }
    }
    for (const p of docPending) {
      out.push({ id: draftId(p.localId), anchor: p.anchor, lineNumber: p.anchor ? undefined : p.lineNumber })
    }
    return out
  }, [comments, docPending, activeId])

  const focusThreadRef = useRef<(id: string, scroll: boolean) => void>(() => {})
  const anchors = useDocumentAnchors(contentRef, threads, {
    activeId,
    content,
    onClickAnchor: (id) => focusThreadRef.current(id, false),
  })
  const { placements, scrollToAnchor } = anchors

  // ── Rail contents ──────────────────────────────────────────────────────────
  const mine = useCallback(
    (c: Comment) => c.authorKind !== 'AGENT' && !!currentUserId && c.authorId === currentUserId,
    [currentUserId]
  )

  /** Whether a thread belongs in the current filter. The focused thread always stays, so acting on it never makes it vanish. */
  const inFilter = useCallback(
    (c: Comment) => {
      if (c.id === activeId) return true
      if (filter === 'resolved') return isResolved(c)
      if (isResolved(c)) return false
      return filter === 'open' || mine(c)
    },
    [filter, activeId, mine]
  )

  const { placed, whole, resolvedStack, unreported } = useMemo(() => {
    const placed: Entry[] = []
    const whole: Entry[] = []
    const resolvedStack: Entry[] = []
    const unreported: Entry[] = []
    for (const c of comments) {
      if (!inFilter(c)) continue
      if (kindOf(c) === 'DOCUMENT') {
        whole.push({ kind: 'comment', id: c.id, comment: c, top: 0 })
        continue
      }
      const spot = placements[c.id]
      if (spot && !spot.found) {
        whole.push({
          kind: 'comment',
          id: c.id,
          comment: c,
          top: 0,
          note: c.anchor ? unplacedNote(c.anchor.exact) : legacyLineNote(c.lineNumber),
        })
      } else if (spot) {
        placed.push({ kind: 'comment', id: c.id, comment: c, top: spot.top })
      } else if (isResolved(c)) {
        resolvedStack.push({ kind: 'comment', id: c.id, comment: c, top: 0 })
      } else {
        unreported.push({ kind: 'comment', id: c.id, comment: c, top: 0 })
      }
    }
    if (filter !== 'resolved') {
      for (const p of docPending) {
        const id = draftId(p.localId)
        const spot = placements[id]
        if (spot && !spot.found) {
          whole.push({
            kind: 'draft',
            id,
            draft: p,
            top: 0,
            note: p.anchor ? unplacedNote(p.anchor.exact) : legacyLineNote(p.lineNumber),
          })
        } else if (spot) {
          placed.push({ kind: 'draft', id, draft: p, top: spot.top })
        } else {
          unreported.push({ kind: 'draft', id, draft: p, top: 0 })
        }
      }
    }
    // Array.sort is stable, so equal tops keep their creation order.
    placed.sort((a, b) => a.top - b.top)
    return { placed, whole, resolvedStack, unreported }
  }, [comments, docPending, placements, inFilter, filter])

  const total = comments.length + docPending.length

  // Open, placed threads in reading order: what ‹ › and j / k step through.
  const navIds = useMemo(
    () => placed.filter((e) => e.kind === 'draft' || !isResolved(e.comment)).map((e) => e.id),
    [placed]
  )
  const navIndex = activeId && navIds.includes(activeId) ? navIds.indexOf(activeId) + 1 : null

  /** Focuses a thread. `scroll` also takes the document to its words and flashes them. */
  const focusThread = useCallback(
    (id: string, scroll: boolean) => {
      setActiveId(id)
      if (scroll && placements[id]?.found) scrollToAnchor(id)
    },
    [placements, scrollToAnchor]
  )
  useEffect(() => {
    focusThreadRef.current = (id, scroll) => {
      focusThread(id, scroll)
      // A click on the words opens the rail when it was closed.
      if (!scroll) setRailChoice(true)
    }
  }, [focusThread])

  const step = useCallback(
    (delta: number) => {
      if (navIds.length === 0) return
      const i = activeId ? navIds.indexOf(activeId) : -1
      const next = i < 0 ? (delta > 0 ? 0 : navIds.length - 1) : (i + delta + navIds.length) % navIds.length
      setRailChoice((c) => c ?? true)
      focusThread(navIds[next], true)
    },
    [navIds, activeId, focusThread]
  )
  const next = useCallback(() => step(1), [step])
  const prev = useCallback(() => step(-1), [step])

  // Escape (outside a text field) drops the focus from the thread.
  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key !== 'Escape' || e.defaultPrevented) return
      const t = e.target as HTMLElement | null
      if (t && (t.tagName === 'TEXTAREA' || t.tagName === 'INPUT' || t.isContentEditable)) return
      setActiveId(null)
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  // The floating rail is a plain list, so a focused card is scrolled into view in it.
  useEffect(() => {
    if (docked || !activeId) return
    const cards = listRef.current?.querySelectorAll<HTMLElement>('[data-comment-id]') ?? []
    Array.from(cards)
      .find((el) => el.dataset.commentId === activeId)
      ?.scrollIntoView?.({ block: 'nearest' })
  }, [docked, activeId])

  // ── Selecting text ─────────────────────────────────────────────────────────
  const evaluateSelection = useCallback(() => {
    const container = contentRef.current
    const wrapper = wrapperRef.current
    const sel = typeof window.getSelection === 'function' ? window.getSelection() : null
    if (!container || !wrapper || !sel || sel.rangeCount === 0 || sel.isCollapsed) {
      setSelection(null)
      return
    }
    const range = sel.getRangeAt(0)
    const anchor = anchorFromRange(container, range)
    if (!anchor) {
      setSelection(null)
      return
    }
    const box = wrapper.getBoundingClientRect()
    const r =
      typeof range.getBoundingClientRect === 'function'
        ? range.getBoundingClientRect()
        : { left: 0, right: 0, top: 0, bottom: 0 }
    const centre = (r.left + r.right) / 2 - box.left - BUTTON_WIDTH / 2
    // The button sits just above the selection so it never covers the words being selected.
    let top = r.top - box.top - BUTTON_HEIGHT - 8
    if (top < 0) top = r.bottom - box.top + 8
    const maxLeft = Math.max(0, box.width - BUTTON_WIDTH - 8)
    setSelection({
      anchor,
      left: Math.min(Math.max(0, centre), maxLeft),
      top,
      selLeft: r.left - box.left,
      bottom: r.bottom - box.top,
    })
  }, [])

  // A selection that collapses (a click elsewhere, Escape) takes the button away.
  useEffect(() => {
    function onChange() {
      const sel = window.getSelection?.()
      if (!sel || sel.isCollapsed) setSelection(null)
    }
    document.addEventListener('selectionchange', onChange)
    return () => document.removeEventListener('selectionchange', onChange)
  }, [])

  function openCompose() {
    if (!selection) return
    const box = wrapperRef.current?.getBoundingClientRect()
    const maxLeft = box ? Math.max(0, box.width - FORM_WIDTH - 8) : selection.selLeft
    setComposing({
      ...selection,
      left: Math.min(Math.max(0, selection.selLeft), maxLeft),
      top: selection.bottom + 8,
    })
    setSelection(null)
  }

  function closeCompose() {
    setComposing(null)
    window.getSelection?.()?.removeAllRanges()
  }

  async function handleSubmit(text: string) {
    if (!composing) return
    const anchor = composing.anchor
    if (reviewMode) {
      onAddPendingComment?.(anchor, text, contentHash ?? undefined)
      closeCompose()
      return
    }
    closeCompose()
    const created = await api.create({
      content: text,
      anchor,
      ...(contentHash ? { documentHash: contentHash } : {}),
    })
    // Focus the new thread so its card is where the reader looks next.
    if (created) setActiveId(created.id)
  }

  // ── Cards ──────────────────────────────────────────────────────────────────
  function renderEntry(e: Entry) {
    if (e.kind === 'draft') {
      return (
        <PendingCommentCard
          key={e.id}
          draft={e.draft}
          active={activeId === e.id}
          note={e.note}
          editing={editingPendingId === e.draft.localId}
          onActivate={(id) => focusThread(id, true)}
          onStartEdit={() => setEditingPendingId(e.draft.localId)}
          onCancelEdit={() => setEditingPendingId(null)}
          onSave={(text) => {
            onEditPendingComment?.(e.draft.localId, text)
            setEditingPendingId(null)
          }}
          onRemove={() => onRemovePendingComment?.(e.draft.localId)}
        />
      )
    }
    return (
      <CommentCard
        key={e.id}
        comment={e.comment}
        active={activeId === e.id}
        note={e.note}
        currentUserId={currentUserId}
        isAdmin={isAdmin}
        onActivate={(id) => focusThread(id, true)}
        onReply={api.reply}
        onEdit={api.edit}
        onEditReply={api.editReply}
        onResolve={api.resolve}
        onReopen={api.reopen}
        onDelete={async (id) => {
          if (activeId === id) setActiveId(null)
          await api.remove(id)
        }}
        onDeleteReply={api.removeReply}
      />
    )
  }

  const railNodes: MarginRailItem[] = placed.map((e) => ({
    id: e.id,
    top: e.top - ANCHOR_INSET,
    node: renderEntry(e),
  }))

  // The markdown is re-created only when its own inputs change, never for a rail or selection update.
  const markdown = useMemo(
    () => (
      <MarkdownRenderer
        content={content}
        onDocumentNavigate={onDocumentNavigate}
        onToggleTask={onToggleTask}
        tasksReadOnly={tasksReadOnly}
        annotateSourceLines
      />
    ),
    [content, onDocumentNavigate, onToggleTask, tasksReadOnly]
  )

  const railBody = (
    <>
      <div className="shrink-0 space-y-3 pb-3">
        <div className="flex items-center justify-between gap-2">
          <h2 className="text-[11.5px] font-semibold uppercase tracking-[0.06em] text-muted-foreground">
            Comments · {total}
          </h2>
          {!docked && (
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7"
              aria-label="Close comments"
              onClick={() => setRailChoice(false)}
            >
              <X className="h-4 w-4" />
            </Button>
          )}
        </div>
        <RailHeader
          filter={filter}
          onFilterChange={setFilter}
          index={navIndex}
          total={navIds.length}
          onPrev={prev}
          onNext={next}
        />
        <div className="max-h-64 overflow-y-auto">
          <WholeDocumentSection
            onCreate={
              reviewMode
                ? undefined
                : async (text) => {
                    const created = await api.create({
                      content: text,
                      ...(contentHash ? { documentHash: contentHash } : {}),
                    })
                    if (created) setActiveId(created.id)
                  }
            }
          >
            {whole.map(renderEntry)}
          </WholeDocumentSection>
        </div>
      </div>

      {docked ? (
        <AlignedRail items={railNodes} activeId={activeId} contentRef={contentRef}>
          {/* Until the highlights have been found there is nothing to line up with, so threads are just listed. */}
          {unreported.length > 0 && (
            <div className="absolute inset-0 space-y-2 overflow-y-auto bg-background">{unreported.map(renderEntry)}</div>
          )}
          {/* Resolved threads have no highlight to line up with, so they are listed. */}
          {filter === 'resolved' && resolvedStack.length > 0 && (
            <div className="absolute inset-0 space-y-2 overflow-y-auto bg-background">
              {resolvedStack.map(renderEntry)}
            </div>
          )}
        </AlignedRail>
      ) : (
        <div ref={listRef} className="min-h-0 flex-1 space-y-2 overflow-y-auto">
          {[...placed, ...unreported, ...resolvedStack].map(renderEntry)}
        </div>
      )}
    </>
  )

  return (
    <div ref={wrapperRef} className="relative space-y-2">
      <div className="flex min-h-8 items-center justify-between gap-3">
        <p className="text-xs text-muted-foreground">Select text in the document to comment on it.</p>
        {!docked && (
          <Button
            variant="ghost"
            size="sm"
            aria-expanded={railOpen}
            aria-controls="document-comment-rail"
            onClick={() => setRailChoice(!railOpen)}
          >
            <MessageSquare className="mr-1.5 h-4 w-4" />
            {railOpen ? 'Hide comments' : 'Comments'} · {total}
          </Button>
        )}
      </div>

      <div className="relative flex gap-4">
        <div
          ref={contentRef}
          className="min-w-0 flex-1"
          onMouseUp={evaluateSelection}
          onKeyUp={evaluateSelection}
        >
          {markdown}
        </div>

        {railOpen && (
          <aside
            id="document-comment-rail"
            aria-label="Comments"
            className={cn(
              'flex w-80 shrink-0 flex-col',
              docked
                ? 'sticky top-2 h-[calc(100vh-7rem)] self-start pr-1'
                : 'fixed inset-y-4 right-4 z-30 max-w-[calc(100vw-2rem)] rounded-lg border border-border bg-card p-3 shadow-xl'
            )}
          >
            {railBody}
          </aside>
        )}
      </div>

      {composing && <div className="fixed inset-0 z-40" onClick={closeCompose} aria-hidden="true" />}

      {selection && !composing && (
        <Button
          size="sm"
          className="absolute z-30 shadow-md"
          aria-label="Comment on selection"
          style={{ left: selection.left, top: selection.top }}
          // Keeps the text selected while the button is pressed.
          onMouseDown={(e) => e.preventDefault()}
          onClick={openCompose}
        >
          <MessageSquarePlus className="mr-1.5 h-4 w-4" />
          Comment
        </Button>
      )}

      {composing && (
        <div
          data-testid="comment-popover"
          className="absolute z-50 w-[21rem] max-w-[calc(100%-1rem)] rounded-lg border border-border bg-card p-4 shadow-xl"
          style={{ left: composing.left, top: composing.top }}
        >
          <blockquote className="mb-3 border-l-4 border-muted pl-3">
            <p className="line-clamp-4 text-xs italic leading-relaxed text-muted-foreground">
              {composing.anchor.exact}
            </p>
          </blockquote>
          <CommentComposer autoFocus onSubmit={handleSubmit} onCancel={closeCompose} />
        </div>
      )}
    </div>
  )
}
