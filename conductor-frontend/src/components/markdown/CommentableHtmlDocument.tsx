'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { MessageSquare, MessageSquarePlus, X } from 'lucide-react'
import { apiPost } from '@/lib/api'
import { cn } from '@/lib/utils'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { CommentThread } from '@/components/comments/CommentThread'
import { NewCommentForm } from '@/components/comments/NewCommentForm'
import { PendingCommentRow } from '@/components/comments/PendingCommentRow'
import type { Comment, PendingCommentDraft, TextQuoteAnchor } from '@/components/comments/types'
import { prepareHtmlDocument } from './htmlDocument'
import { bridgeSource } from './htmlBridge'
import {
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
  /** Hash of `content`, sent with a new comment so the server can tell which version it was written against. */
  contentHash?: string | null
  documentId: string
  issueId: string
  projectId: string
  comments: Comment[]
  onCommentAdded: () => void
  token: string
  currentUserId: string
  /** Override the base path for comment API calls. Defaults to the Work Item comment path. */
  commentApiBasePath?: string
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
/** Below this width the frame plus a docked rail would squeeze the document, so the rail floats over it. */
const DOCK_MIN_WIDTH = 1024

type RailItem =
  | { kind: 'comment'; id: string; comment: Comment; top: number }
  | { kind: 'draft'; id: string; draft: PendingCommentDraft; top: number }

const SECTION_LABEL =
  'text-[11.5px] font-semibold uppercase tracking-[0.06em] text-muted-foreground'

/** Small stable hash, so the viewer can be remounted (clearing placements and selection) when the document changes. */
function hashContent(s: string): string {
  let h = 5381
  for (let i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0
  return `${s.length}:${h}`
}

/** An HTML document: sandboxed frame on the left, comment rail on the right (stacked below on narrow screens). */
export function CommentableHtmlDocument(props: Props) {
  const { documentId, content } = props
  const frameKey = useMemo(() => `${documentId}:${hashContent(content)}`, [documentId, content])
  return <HtmlDocumentFrame key={frameKey} {...props} />
}

function HtmlDocumentFrame({
  content,
  fullView = false,
  contentHash,
  documentId,
  issueId,
  projectId,
  comments,
  onCommentAdded,
  token,
  currentUserId,
  commentApiBasePath,
  reviewMode = false,
  pendingComments = [],
  onAddPendingComment,
  onEditPendingComment,
  onRemovePendingComment,
}: Props) {
  const basePath =
    commentApiBasePath ?? `/api/v2/projects/${projectId}/work-items/${issueId}/comments`

  const iframeRef = useRef<HTMLIFrameElement>(null)
  const frameBoxRef = useRef<HTMLDivElement>(null)
  const wrapperRef = useRef<HTMLDivElement>(null)
  const railItemRefs = useRef(new Map<string, HTMLDivElement>())

  const [readyCount, setReadyCount] = useState(0)
  const [placement, setPlacement] = useState<Record<string, AnchorResult>>({})
  const [selection, setSelection] = useState<FloatingSpot | null>(null)
  const [composing, setComposing] = useState<FloatingSpot | null>(null)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [editingPendingId, setEditingPendingId] = useState<string | null>(null)
  // 0 until measured (and always in tests): treated as wide enough to dock.
  const [wrapperWidth, setWrapperWidth] = useState(0)
  const docked = wrapperWidth === 0 || wrapperWidth >= DOCK_MIN_WIDTH
  // null = follow the default (docked: open, floating: closed); a boolean once the reader toggles it.
  const [railChoice, setRailChoice] = useState<boolean | null>(null)
  // Bumped by a highlight click; focusing waits for the rail to render, since it may have been closed.
  const [focusRequest, setFocusRequest] = useState<{ id: string; n: number } | null>(null)

  useEffect(() => {
    const el = wrapperRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(([entry]) => setWrapperWidth(Math.round(entry.contentRect.width)))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const srcDoc = useMemo(() => prepareHtmlDocument(content, { bridge: bridgeSource }), [content])

  const docPending = useMemo(
    () => pendingComments.filter((p) => p.documentId === documentId),
    [pendingComments, documentId]
  )

  // ── Anchors sent to the frame ──────────────────────────────────────────────
  const frameAnchors = useMemo<FrameAnchor[]>(() => {
    const out: FrameAnchor[] = []
    for (const c of comments) {
      if (c.anchor && !c.resolvedAt) {
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
  }, [comments, docPending])
  const anchorsKey = JSON.stringify(frameAnchors)

  const sendToFrame = useCallback((message: unknown) => {
    iframeRef.current?.contentWindow?.postMessage(message, '*')
  }, [])

  useEffect(() => {
    if (readyCount === 0) return
    sendToFrame({ type: 'anchors', anchors: JSON.parse(anchorsKey) })
  }, [readyCount, anchorsKey, sendToFrame])

  // ── Messages from the frame ────────────────────────────────────────────────
  // The button sits just above the selection, centred on it, so it never covers the text being selected;
  // it drops below only when the selection is at the top edge of the frame.
  const floatingSpot = useCallback((anchor: TextQuoteAnchor, rect: SelectionRect): FloatingSpot => {
    const box = frameBoxRef.current
    const width = box?.clientWidth ?? 0
    let left = (rect.left + rect.right) / 2 - BUTTON_WIDTH / 2
    let top = rect.top - BUTTON_HEIGHT - 8
    if (top < 8) top = rect.bottom + 8
    if (width > 0) left = Math.min(left, width - BUTTON_WIDTH - 8)
    return { anchor, rect, left: Math.max(8, left), top: Math.max(8, top) }
  }, [])

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
        case 'openLink':
          openFrameLink(msg.href)
          break
        case 'open':
          // The rail may be closed; open it and focus the thread once it has rendered.
          setActiveId(msg.id)
          setRailChoice(true)
          setFocusRequest((r) => ({ id: msg.id, n: (r?.n ?? 0) + 1 }))
          break
      }
    }
    window.addEventListener('message', onMessage)
    return () => window.removeEventListener('message', onMessage)
  }, [floatingSpot])

  // ── Rail contents ──────────────────────────────────────────────────────────
  const { placed, unplaced, resolved } = useMemo(() => {
    const placed: RailItem[] = []
    const unplaced: RailItem[] = []
    const resolved: Comment[] = []
    for (const c of comments) {
      if (c.resolvedAt) {
        resolved.push(c)
        continue
      }
      if (!c.anchor) {
        // A line comment left before the document became HTML: nothing to find.
        unplaced.push({ kind: 'comment', id: c.id, comment: c, top: 0 })
        continue
      }
      const spot = placement[c.id]
      if (spot && !spot.found) unplaced.push({ kind: 'comment', id: c.id, comment: c, top: 0 })
      // Not reported yet: listed after the placed ones until the frame answers.
      else placed.push({ kind: 'comment', id: c.id, comment: c, top: spot ? spot.top : Infinity })
    }
    for (const p of docPending) {
      const id = draftId(p.localId)
      const spot = placement[id]
      if (spot && !spot.found) unplaced.push({ kind: 'draft', id, draft: p, top: 0 })
      else placed.push({ kind: 'draft', id, draft: p, top: spot ? spot.top : Infinity })
    }
    // Array.sort is stable, so equal tops keep their creation order.
    placed.sort((a, b) => (a.top === b.top ? 0 : a.top < b.top ? -1 : 1))
    return { placed, unplaced, resolved }
  }, [comments, docPending, placement])

  const total = placed.length + unplaced.length + resolved.length
  const railOpen = total > 0 && (railChoice ?? docked)

  useEffect(() => {
    if (!focusRequest || !railOpen) return
    const el = railItemRefs.current.get(focusRequest.id)
    el?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
    el?.focus({ preventScroll: true })
  }, [focusRequest, railOpen])

  function focusThread(item: RailItem) {
    setActiveId(item.id)
    if (placement[item.id]?.found) sendToFrame({ type: 'scrollTo', id: item.id })
  }

  function openCompose() {
    if (!selection) return
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
    } else {
      await apiPost(
        basePath,
        {
          documentId,
          content: text,
          anchor: composing.anchor,
          ...(contentHash ? { documentHash: contentHash } : {}),
        },
        token
      )
      onCommentAdded()
    }
    setComposing(null)
  }

  function renderItem(item: RailItem) {
    return (
      <div
        key={item.id}
        id={`html-comment-${item.id}`}
        data-comment-id={item.id}
        ref={(el) => {
          if (el) railItemRefs.current.set(item.id, el)
          else railItemRefs.current.delete(item.id)
        }}
        tabIndex={-1}
        onClick={(e) => {
          if ((e.target as HTMLElement).closest('button,textarea,input,a')) return
          focusThread(item)
        }}
        className={cn(
          'rounded-lg outline-none transition-shadow',
          activeId === item.id && 'ring-2 ring-primary'
        )}
      >
        {item.kind === 'comment' ? (
          <>
            {item.comment.outdated && (
              <Badge variant="status-progress" className="mb-1">
                Outdated
              </Badge>
            )}
            <CommentThread
              comment={item.comment}
              projectId={projectId}
              issueId={issueId}
              currentUserId={currentUserId}
              token={token}
              onUpdated={onCommentAdded}
              commentApiBasePath={commentApiBasePath}
            />
          </>
        ) : (
          <div className="w-72">
            {item.draft.anchor && (
              <blockquote className="border-l-4 border-muted pl-3 mb-1">
                <p className="text-xs text-muted-foreground italic leading-relaxed line-clamp-3">
                  {item.draft.anchor.exact}
                </p>
              </blockquote>
            )}
            <PendingCommentRow
              draft={item.draft}
              editing={editingPendingId === item.draft.localId}
              onStartEdit={() => setEditingPendingId(item.draft.localId)}
              onCancelEdit={() => setEditingPendingId(null)}
              onSave={(text) => {
                onEditPendingComment?.(item.draft.localId, text)
                setEditingPendingId(null)
              }}
              onRemove={() => onRemovePendingComment?.(item.draft.localId)}
            />
          </div>
        )}
      </div>
    )
  }

  return (
    <div ref={wrapperRef} className="space-y-2">
      <div className="flex items-center justify-between gap-3 min-h-8">
        <p className="text-xs text-muted-foreground">Select text in the document to comment on it.</p>
        {total > 0 && (
          <Button
            variant="ghost"
            size="sm"
            aria-expanded={railOpen}
            aria-controls="html-comment-rail"
            onClick={() => setRailChoice(!railOpen)}
          >
            <MessageSquare className="h-4 w-4 mr-1.5" />
            {railOpen ? 'Hide comments' : 'Comments'} · {total}
          </Button>
        )}
      </div>

      <div
        className={cn(
          'relative flex gap-4 min-h-[28rem]',
          fullView ? 'h-[calc(100vh-9rem)]' : 'h-[70vh] lg:h-[calc(100vh-17rem)]'
        )}
      >
        {composing && (
          <div className="fixed inset-0 z-40" onClick={() => setComposing(null)} aria-hidden="true" />
        )}

        <div
          ref={frameBoxRef}
          className="relative flex-1 min-w-0 rounded-lg border border-border overflow-hidden"
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
              <MessageSquarePlus className="h-4 w-4 mr-1.5" />
              Comment
            </Button>
          )}

          {composing && (
            <div
              data-testid="comment-popover"
              className="absolute z-50 w-[21rem] max-w-[calc(100%-1rem)] bg-card border border-border rounded-lg shadow-xl p-4"
              style={{
                left: composing.left,
                top: composing.top,
              }}
            >
              <blockquote className="border-l-4 border-muted pl-3 mb-3">
                <p className="text-xs text-muted-foreground italic leading-relaxed line-clamp-4">
                  {composing.anchor.exact}
                </p>
              </blockquote>
              <NewCommentForm
                onSubmit={handleSubmit}
                onCancel={() => setComposing(null)}
                size="compact"
              />
            </div>
          )}
        </div>

        {railOpen && (
          <aside
            id="html-comment-rail"
            aria-label="Comments"
            className={cn(
              'w-80 max-w-[calc(100%-2rem)] shrink-0 overflow-y-auto space-y-3',
              docked
                ? 'pr-1'
                : 'absolute right-0 inset-y-0 z-20 bg-card border border-border rounded-lg shadow-xl p-3'
            )}
          >
            <div className="flex items-center justify-between">
              <h2 className={SECTION_LABEL}>Comments · {total}</h2>
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
            {placed.map(renderItem)}

            {unplaced.length > 0 && (
              <section aria-label="Unplaced comments" className="space-y-3 pt-2">
                <h3 className={SECTION_LABEL}>Unplaced</h3>
                <p className="text-xs text-muted-foreground">
                  These comments can&rsquo;t be placed in the current version of the document.
                </p>
                {unplaced.map(renderItem)}
              </section>
            )}

            {resolved.length > 0 && (
              <section aria-label="Resolved comments" className="space-y-3 pt-2">
                <h3 className={SECTION_LABEL}>Resolved</h3>
                {resolved.map((c) =>
                  renderItem({ kind: 'comment', id: c.id, comment: c, top: 0 })
                )}
              </section>
            )}
          </aside>
        )}
      </div>
    </div>
  )
}

function draftId(localId: string): string {
  return `draft:${localId}`
}
