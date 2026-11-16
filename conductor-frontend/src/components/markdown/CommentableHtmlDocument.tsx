'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { MessageSquare, MessageSquarePlus } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { CommentCard, PendingCommentCard } from '@/components/comments/CommentCard'
import { CommentComposer } from '@/components/comments/CommentComposer'
import { CommentSheet, type CommentSheetView } from '@/components/comments/CommentSheet'
import { CountBubbles, type CountBubbleItem } from '@/components/comments/CountBubbles'
import { MarginRail, type MarginRailItem } from '@/components/comments/MarginRail'
import { RailHeader, type RailFilter } from '@/components/comments/RailHeader'
import { WholeDocumentSection, legacyLineNote, unplacedNote } from '@/components/comments/WholeDocumentSection'
import { useComments } from '@/components/comments/useComments'
import { isCoarsePointer, useCommentLayoutMode } from '@/components/comments/useCommentLayoutMode'
import { selectionButtonTop } from '@/components/comments/selectionPlacement'
import type { Comment, PendingCommentDraft, TextQuoteAnchor } from '@/components/comments/types'
import { prepareHtmlDocument } from './htmlDocument'
import { bridgeSource } from './htmlBridge'
import {
  buildThemeMessage,
  openFrameLink,
  parseBridgeMessage,
  type AnchorResult,
  type FrameAnchor,
  type SelectionRect,
} from './htmlBridgeProtocol'

interface Props {
  content: string
  /** Shown on its own over the whole window, so the frame can take the window's height. */
  fullView?: boolean
  /** A control for the right end of the toolbar, after the comments toggle (the Full view button). */
  toolbarAction?: React.ReactNode
  /** Hash of `content`, sent with a new comment so the server can tell which version it was written against. */
  contentHash?: string | null
  documentId: string
  /** The Work Item the document belongs to. */
  issueId: string
  projectId: string
  token: string
  currentUserId: string
  currentUserName?: string
  /** Admins may delete anyone's comment. */
  isAdmin?: boolean
  /** Called after a comment change has been saved, so counts elsewhere on the page can refresh. */
  onCommentsChanged?: () => void
  /** Batch review mode: a new comment is held client-side via `onAddPendingComment` instead of posted. */
  reviewMode?: boolean
  /** All of the Work Item's pending drafts — filtered internally to this component's `documentId`. */
  pendingComments?: PendingCommentDraft[]
  onAddPendingComment?: (anchor: TextQuoteAnchor, content: string, documentHash?: string) => void
  onEditPendingComment?: (localId: string, content: string) => void
  onRemovePendingComment?: (localId: string) => void
}

interface FloatingSpot {
  anchor: TextQuoteAnchor
  left: number
  top: number
  /** The selection's box inside the frame, so the compose form can sit next to it without covering it. */
  rect: SelectionRect
}

const BUTTON_WIDTH = 112
const BUTTON_HEIGHT = 32
const FORM_WIDTH = 336
const FORM_HEIGHT = 240
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

/** Small stable hash, so the viewer can be remounted (clearing placements and selection) when the document changes. */
function hashContent(s: string): string {
  let h = 5381
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0
  return `${s.length}:${h}`
}

/** The highlight colours from the app's theme tokens, as the plain colour strings the frame can use. */
function readTheme() {
  const style = getComputedStyle(document.documentElement)
  const base = style.getPropertyValue('--comment-highlight').trim()
  const active = style.getPropertyValue('--comment-highlight-active').trim()
  if (!base || !active) return null
  return buildThemeMessage(`hsl(${base})`, `hsl(${active})`)
}

/**
 * An HTML document: the sandboxed frame on the left and, from 1024px wide, a comment rail docked on the
 * right whose cards line up with their highlights inside the frame. On a tablet the rail shrinks to count
 * bubbles that open a side sheet; on a phone a tap on a highlight opens a bottom sheet.
 */
export function CommentableHtmlDocument(props: Props) {
  const { documentId, content } = props
  const frameKey = useMemo(() => `${documentId}:${hashContent(content)}`, [documentId, content])
  return <HtmlDocumentFrame key={frameKey} {...props} />
}

function HtmlDocumentFrame({
  content,
  fullView = false,
  toolbarAction,
  contentHash,
  documentId,
  issueId,
  projectId,
  token,
  currentUserId,
  currentUserName,
  isAdmin = false,
  onCommentsChanged,
  reviewMode = false,
  pendingComments = [],
  onAddPendingComment,
  onEditPendingComment,
  onRemovePendingComment,
}: Props) {
  const api = useComments(
    { projectId, workItemId: issueId, documentId },
    {
      token,
      currentUser: { id: currentUserId, name: currentUserName ?? 'You' },
      onChanged: onCommentsChanged,
    }
  )
  const comments = api.comments

  const iframeRef = useRef<HTMLIFrameElement>(null)
  const frameBoxRef = useRef<HTMLDivElement>(null)
  const wrapperRef = useRef<HTMLDivElement>(null)
  const railColumnRef = useRef<HTMLDivElement>(null)
  const railTopRef = useRef<HTMLDivElement>(null)
  const alignedRef = useRef<HTMLDivElement>(null)

  const [readyCount, setReadyCount] = useState(0)
  const [placement, setPlacement] = useState<Record<string, AnchorResult>>({})
  const [layout, setLayout] = useState<{ scrollY: number; tops: Record<string, number> }>({
    scrollY: 0,
    tops: {},
  })
  const [selection, setSelection] = useState<FloatingSpot | null>(null)
  const [composing, setComposing] = useState<FloatingSpot | null>(null)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [filter, setFilter] = useState<RailFilter>('open')
  const [editingPendingId, setEditingPendingId] = useState<string | null>(null)
  // Distance from the top of the rail to the top of the aligned area, so cards line up with the frame.
  const [railOffset, setRailOffset] = useState(0)
  // 0 until measured (and always in tests): treated as wide enough to dock.
  const [wrapperWidth, setWrapperWidth] = useState(0)
  const mode = useCommentLayoutMode(wrapperWidth)
  const docked = mode === 'wide'
  // Narrow screens show threads in a sheet: one thread (tap on a highlight or bubble) or the whole list.
  const [sheetView, setSheetView] = useState<'thread' | 'list' | null>(null)

  useEffect(() => {
    const el = wrapperRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(([entry]) => setWrapperWidth(Math.round(entry.contentRect.width)))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  useEffect(() => {
    const top = railTopRef.current
    const column = railColumnRef.current
    const aligned = alignedRef.current
    // The tablet's bubble column has no header above it, so there is nothing to measure.
    if (!top || !column || !aligned || typeof ResizeObserver === 'undefined') return
    const measure = () =>
      setRailOffset(Math.round(aligned.getBoundingClientRect().top - column.getBoundingClientRect().top))
    measure()
    const ro = new ResizeObserver(measure)
    ro.observe(top)
    ro.observe(column)
    return () => ro.disconnect()
  }, [docked])

  const srcDoc = useMemo(() => prepareHtmlDocument(content, { bridge: bridgeSource }), [content])

  const docPending = useMemo(
    () => pendingComments.filter((p) => p.documentId === documentId),
    [pendingComments, documentId]
  )

  const sendToFrame = useCallback((message: unknown) => {
    iframeRef.current?.contentWindow?.postMessage(message, '*')
  }, [])

  // ── Anchors sent to the frame ──────────────────────────────────────────────
  // Open threads are highlighted. A thread that was just resolved keeps its highlight while it is the
  // focused one, so it does not vanish from under the reader's cursor.
  const frameAnchors = useMemo<FrameAnchor[]>(() => {
    const out: FrameAnchor[] = []
    for (const c of comments) {
      if (!c.anchor) continue
      if (!isResolved(c) || c.id === activeId) {
        out.push({ id: c.id, exact: c.anchor.exact, prefix: c.anchor.prefix, suffix: c.anchor.suffix })
      }
    }
    for (const p of docPending) {
      if (p.anchor) {
        out.push({
          id: draftId(p.localId),
          exact: p.anchor.exact,
          prefix: p.anchor.prefix,
          suffix: p.anchor.suffix,
        })
      }
    }
    return out
  }, [comments, docPending, activeId])
  const anchorsKey = JSON.stringify(frameAnchors)

  useEffect(() => {
    if (readyCount === 0) return
    sendToFrame({ type: 'anchors', anchors: JSON.parse(anchorsKey) })
  }, [readyCount, anchorsKey, sendToFrame])

  useEffect(() => {
    if (readyCount === 0) return
    sendToFrame({ type: 'active', id: activeId })
  }, [readyCount, activeId, sendToFrame])

  // The frame cannot read the app's theme tokens, so it is told the highlight colours, again whenever
  // the theme changes.
  useEffect(() => {
    if (readyCount === 0) return
    const send = () => {
      const msg = readTheme()
      if (msg) sendToFrame(msg)
    }
    send()
    const mo = new MutationObserver(send)
    mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme', 'style'] })
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)')
    mq?.addEventListener?.('change', send)
    return () => {
      mo.disconnect()
      mq?.removeEventListener?.('change', send)
    }
  }, [readyCount, sendToFrame])

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
      const kind = kindOf(c)
      if (kind === 'DOCUMENT') {
        whole.push({ kind: 'comment', id: c.id, comment: c, top: 0 })
      } else if (kind === 'LINE' || !c.anchor) {
        whole.push({ kind: 'comment', id: c.id, comment: c, top: 0, note: legacyLineNote(c.lineNumber) })
      } else {
        const spot = placement[c.id]
        const layoutTop = layout.tops[c.id]
        if (spot && !spot.found) {
          whole.push({
            kind: 'comment',
            id: c.id,
            comment: c,
            top: 0,
            note: unplacedNote(c.anchor.exact),
          })
        } else if (spot) {
          placed.push({ kind: 'comment', id: c.id, comment: c, top: layoutTop ?? spot.top })
        } else if (isResolved(c)) {
          resolvedStack.push({ kind: 'comment', id: c.id, comment: c, top: 0 })
        } else {
          unreported.push({ kind: 'comment', id: c.id, comment: c, top: 0 })
        }
      }
    }
    if (filter !== 'resolved') {
      for (const p of docPending) {
        const id = draftId(p.localId)
        const spot = placement[id]
        if (spot && !spot.found) {
          whole.push({
            kind: 'draft',
            id,
            draft: p,
            top: 0,
            note: p.anchor ? unplacedNote(p.anchor.exact) : legacyLineNote(p.lineNumber),
          })
        } else if (spot) {
          placed.push({ kind: 'draft', id, draft: p, top: layout.tops[id] ?? spot.top })
        } else {
          unreported.push({ kind: 'draft', id, draft: p, top: 0 })
        }
      }
    }
    // Array.sort is stable, so equal tops keep their creation order.
    placed.sort((a, b) => a.top - b.top)
    return { placed, whole, resolvedStack, unreported }
  }, [comments, docPending, placement, layout.tops, inFilter, filter])

  const total = comments.length + docPending.length

  // Open, placed threads in reading order: what ‹ › and j / k step through.
  const navIds = useMemo(
    () =>
      placed
        .filter((e) => e.kind === 'draft' || !isResolved(e.comment))
        .map((e) => e.id),
    [placed]
  )
  const navIndex = activeId && navIds.includes(activeId) ? navIds.indexOf(activeId) + 1 : null

  /** Focuses a thread. `scroll` also takes the document to its words and flashes them. */
  const focusThread = useCallback(
    (id: string, scroll: boolean) => {
      setActiveId(id)
      if (scroll && placement[id]?.found) sendToFrame({ type: 'scrollTo', id })
    },
    [placement, sendToFrame]
  )

  const step = useCallback(
    (delta: number) => {
      if (navIds.length === 0) return
      const i = activeId ? navIds.indexOf(activeId) : -1
      const next = i < 0 ? (delta > 0 ? 0 : navIds.length - 1) : (i + delta + navIds.length) % navIds.length
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

  // ── Messages from the frame ────────────────────────────────────────────────
  // The button sits just above the selection, centred on it, so it never covers the text being selected;
  // it drops below only when the selection is at the top edge of the frame.
  const floatingSpot = useCallback((anchor: TextQuoteAnchor, rect: SelectionRect): FloatingSpot => {
    const box = frameBoxRef.current
    const width = box?.clientWidth ?? 0
    let left = (rect.left + rect.right) / 2 - BUTTON_WIDTH / 2
    // On touch the button goes below the selection, clear of the native menu that appears above it.
    const top = selectionButtonTop({
      rectTop: rect.top,
      rectBottom: rect.bottom,
      buttonHeight: BUTTON_HEIGHT,
      coarse: isCoarsePointer(),
      minTop: 8,
    })
    if (width > 0) left = Math.min(left, width - BUTTON_WIDTH - 8)
    return { anchor, rect, left: Math.max(8, left), top: Math.max(8, top) }
  }, [])

  const stepRef = useRef(step)
  const modeRef = useRef(mode)
  useEffect(() => {
    stepRef.current = step
    modeRef.current = mode
  })

  useEffect(() => {
    function onMessage(event: MessageEvent) {
      // Only the frame this component rendered, and only while it is still the sandboxed srcdoc document.
      // The page inside can post anything it likes, and could navigate itself to another site: the
      // `contentWindow` object survives that, but the opaque origin ("null") of a sandboxed srcdoc
      // frame without allow-same-origin does not.
      if (event.source !== iframeRef.current?.contentWindow) return
      if (event.origin !== 'null') return
      const msg = parseBridgeMessage(event.data)
      if (!msg) return
      switch (msg.type) {
        case 'ready':
          setReadyCount((n) => n + 1)
          break
        case 'selection':
          setSelection(floatingSpot(msg.anchor, msg.rect))
          break
        case 'selection-cleared':
          setSelection(null)
          break
        case 'anchored':
          setPlacement(Object.fromEntries(msg.results.map((r) => [r.id, r])))
          break
        case 'layout':
          setLayout({
            scrollY: msg.scrollY,
            tops: Object.fromEntries(msg.tops.map((t) => [t.id, t.top])),
          })
          break
        case 'openLink':
          openFrameLink(msg.href)
          break
        case 'key':
          if (msg.key === 'Escape') setActiveId(null)
          else stepRef.current(msg.key === 'j' ? 1 : -1)
          break
        case 'open':
          // The thread's words are already on screen: focus its card, or open it in the sheet when there
          // is no docked rail.
          setActiveId(msg.id)
          if (modeRef.current !== 'wide') setSheetView('thread')
          break
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [floatingSpot])

  // ── Composing ──────────────────────────────────────────────────────────────
  function openCompose() {
    if (!selection) return
    // In a sheet the form takes the sheet's width: no popover to position.
    if (!docked) {
      setComposing({ ...selection, left: 0, top: 0 })
      setSelection(null)
      return
    }
    // The form opens below the selection (above it near the bottom edge), left-aligned with it, so the
    // quoted text stays readable while typing.
    const box = frameBoxRef.current
    const boxWidth = box?.clientWidth ?? 0
    const boxHeight = box?.clientHeight ?? 0
    const { rect } = selection
    let top = rect.bottom + 8
    if (boxHeight > 0 && top + FORM_HEIGHT > boxHeight) top = rect.top - FORM_HEIGHT - 8
    let left = rect.left
    if (boxWidth > 0) left = Math.min(left, boxWidth - FORM_WIDTH - 8)
    setComposing({ ...selection, left: Math.max(8, left), top: Math.max(8, top) })
    setSelection(null)
  }

  async function handleSubmit(text: string) {
    if (!composing) return
    if (reviewMode) {
      onAddPendingComment?.(composing.anchor, text, contentHash ?? undefined)
      setComposing(null)
      return
    }
    const anchor = composing.anchor
    setComposing(null)
    const created = await api.create({
      content: text,
      anchor,
      ...(contentHash ? { documentHash: contentHash } : {}),
    })
    // Focus the new thread so its card is where the reader looks next.
    if (created) {
      setActiveId(created.id)
      // With no docked rail, the new thread is shown in the sheet.
      if (!docked) setSheetView('thread')
    }
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

  const bubbleItems: CountBubbleItem[] = placed.map((e) => ({
    id: e.id,
    top: e.top,
    count: e.kind === 'draft' ? 1 : 1 + e.comment.replies.length,
  }))

  const wholeSection = (
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
  )

  const railFilters = (
    <RailHeader
      filter={filter}
      onFilterChange={setFilter}
      index={navIndex}
      total={navIds.length}
      onPrev={prev}
      onNext={next}
    />
  )

  const railBody = (
    <>
      <div ref={railTopRef} className="shrink-0 space-y-3 pb-3">
        <h2 className="text-[11.5px] font-semibold uppercase tracking-[0.06em] text-muted-foreground">
          Comments · {total}
        </h2>
        {railFilters}
        {wholeSection}
      </div>

      <div ref={alignedRef} className="relative min-h-0 flex-1">
        <MarginRail items={railNodes} activeId={activeId} offset={layout.scrollY + railOffset} />
        {/* Until the frame has said it is ready there is nothing to line up with, so threads are just listed. */}
        {readyCount === 0 && unreported.length > 0 && (
          <div className="absolute inset-0 space-y-2 overflow-y-auto bg-background">
            {unreported.map(renderEntry)}
          </div>
        )}
        {/* Resolved threads have no highlight to line up with, so they are listed. */}
        {filter === 'resolved' && resolvedStack.length > 0 && (
          <div className="absolute inset-0 space-y-2 overflow-y-auto bg-background">
            {resolvedStack.map(renderEntry)}
          </div>
        )}
      </div>
    </>
  )

  // ── The sheet (tablet and phone) ───────────────────────────────────────────
  const allEntries = [...placed, ...whole, ...unreported, ...resolvedStack]
  const focusedEntry = activeId ? allEntries.find((e) => e.id === activeId) : undefined
  const sheetOpenView: CommentSheetView | null = docked
    ? null
    : composing
      ? { kind: 'compose', quote: composing.anchor.exact }
      : sheetView === 'thread' && activeId
        ? { kind: 'thread' }
        : sheetView === 'list'
          ? { kind: 'list' }
          : null

  function closeSheet() {
    setComposing(null)
    setSheetView(null)
    setActiveId(null)
  }

  return (
    <div ref={wrapperRef} className="space-y-2">
      <div className="flex min-h-8 items-center justify-between gap-3">
        <p className="text-xs text-muted-foreground">Select text in the document to comment on it.</p>
        <div className="flex items-center gap-1">
          {!docked && (
            <Button variant="ghost" size="sm" onClick={() => setSheetView('list')}>
              <MessageSquare className="mr-1.5 h-4 w-4" />
              Comments · {total}
            </Button>
          )}
          {toolbarAction}
        </div>
      </div>

      <div
        className={cn(
          'relative flex min-h-[28rem] gap-4',
          fullView ? 'h-[calc(100vh-9rem)]' : 'h-[70vh] lg:h-[calc(100vh-17rem)]'
        )}
      >
        {composing && docked && (
          <div className="fixed inset-0 z-40" onClick={() => setComposing(null)} aria-hidden="true" />
        )}

        <div
          ref={frameBoxRef}
          className="relative min-w-0 flex-1 overflow-hidden rounded-lg border border-border"
        >
          <iframe
            ref={iframeRef}
            srcDoc={srcDoc}
            sandbox="allow-scripts"
            className="absolute inset-0 h-full w-full border-0"
            title="HTML document preview"
          />

          {selection && !composing && (
            <Button
              size="sm"
              className="absolute z-30 shadow-md"
              style={{ left: selection.left, top: selection.top }}
              onClick={openCompose}
            >
              <MessageSquarePlus className="mr-1.5 h-4 w-4" />
              Comment
            </Button>
          )}

          {composing && docked && (
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
              <CommentComposer autoFocus onSubmit={handleSubmit} onCancel={() => setComposing(null)} />
            </div>
          )}
        </div>

        {mode === 'wide' && (
          <aside
            id="html-comment-rail"
            ref={railColumnRef}
            aria-label="Comments"
            className="flex w-80 max-w-[calc(100%-2rem)] shrink-0 flex-col pr-1"
          >
            {railBody}
          </aside>
        )}

        {mode === 'tablet' && (
          <aside id="html-comment-bubbles" aria-label="Comments" className="flex w-8 shrink-0 flex-col">
            <div className="relative min-h-0 flex-1">
              <CountBubbles
                items={bubbleItems}
                activeId={activeId}
                offset={layout.scrollY}
                onSelect={(id) => {
                  setActiveId(id)
                  setSheetView('thread')
                }}
              />
            </div>
          </aside>
        )}
      </div>

      {!docked && (
        <CommentSheet
          mode={mode === 'phone' ? 'phone' : 'tablet'}
          view={sheetOpenView}
          total={total}
          onClose={closeSheet}
          onShowAll={() => setSheetView('list')}
          thread={focusedEntry ? renderEntry(focusedEntry) : undefined}
          nav={{ index: navIndex, total: navIds.length, onPrev: prev, onNext: next }}
          listHeader={
            <>
              {railFilters}
              {wholeSection}
            </>
          }
          list={[...placed, ...unreported, ...resolvedStack].map(renderEntry)}
          onSubmitCompose={handleSubmit}
        />
      )}
    </div>
  )
}
