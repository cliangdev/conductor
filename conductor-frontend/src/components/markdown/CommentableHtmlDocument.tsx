'use client'

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { MessageSquarePlus } from 'lucide-react'
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
  parseBridgeMessage,
  type AnchorResult,
  type FrameAnchor,
  type SelectionRect,
} from './htmlBridgeProtocol'

interface Props {
  content: string
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
  onAddPendingComment?: (anchor: TextQuoteAnchor, content: string) => void
  onEditPendingComment?: (localId: string, content: string) => void
  onRemovePendingComment?: (localId: string) => void
}

interface FloatingSpot {
  anchor: TextQuoteAnchor
  left: number
  top: number
}

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
  return <HtmlDocumentFrame key={`${props.documentId}:${hashContent(props.content)}`} {...props} />
}

function HtmlDocumentFrame({
  content,
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
  const railItemRefs = useRef(new Map<string, HTMLDivElement>())

  const [readyCount, setReadyCount] = useState(0)
  const [placement, setPlacement] = useState<Record<string, AnchorResult>>({})
  const [selection, setSelection] = useState<FloatingSpot | null>(null)
  const [composing, setComposing] = useState<FloatingSpot | null>(null)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [editingPendingId, setEditingPendingId] = useState<string | null>(null)

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
  const floatingSpot = useCallback((anchor: TextQuoteAnchor, rect: SelectionRect): FloatingSpot => {
    const box = frameBoxRef.current
    const width = box?.clientWidth ?? 0
    const height = box?.clientHeight ?? 0
    let left = rect.right
    let top = rect.bottom + 8
    if (width > 0) left = Math.max(8, Math.min(left, width - 112))
    if (height > 0 && top > height - 44) top = Math.max(8, rect.top - 44)
    return { anchor, left: Math.max(0, left), top: Math.max(0, top) }
  }, [])

  useEffect(() => {
    function onMessage(event: MessageEvent) {
      // Only the frame this component rendered; the page inside it can post anything it likes.
      if (event.source !== iframeRef.current?.contentWindow) return
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
        case 'open': {
          setActiveId(msg.id)
          const el = railItemRefs.current.get(msg.id)
          el?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
          el?.focus({ preventScroll: true })
          break
        }
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

  function focusThread(item: RailItem) {
    setActiveId(item.id)
    if (placement[item.id]?.found) sendToFrame({ type: 'scrollTo', id: item.id })
  }

  function openCompose() {
    if (!selection) return
    // The form is wider than the button, so pull it back inside the frame.
    const boxWidth = frameBoxRef.current?.clientWidth ?? 0
    const left = boxWidth > 0 ? Math.max(0, Math.min(selection.left, boxWidth - 336)) : selection.left
    setComposing({ ...selection, left })
    setSelection(null)
  }

  async function handleSubmit(text: string) {
    if (!composing) return
    if (reviewMode) {
      onAddPendingComment?.(composing.anchor, text)
    } else {
      await apiPost(basePath, { documentId, content: text, anchor: composing.anchor }, token)
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
    <div className="flex flex-col lg:flex-row gap-4 lg:h-[calc(100vh-15rem)] lg:min-h-[32rem]">
      {composing && (
        <div className="fixed inset-0 z-40" onClick={() => setComposing(null)} aria-hidden="true" />
      )}

      <div
        ref={frameBoxRef}
        className="relative flex-1 min-w-0 h-[70vh] lg:h-auto rounded-lg border border-border overflow-hidden"
      >
        <iframe
          ref={iframeRef}
          srcDoc={srcDoc}
          sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"
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
            className="absolute z-50 w-80 max-w-[calc(100%-1rem)] bg-card border border-border rounded-lg shadow-xl p-4"
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

      <aside
        aria-label="Comments"
        className="lg:w-80 shrink-0 lg:overflow-y-auto lg:pr-1 space-y-3"
      >
        <h2 className={SECTION_LABEL}>
          Comments{total > 0 ? ` · ${total}` : ''}
        </h2>
        {total === 0 && (
          <p className="text-sm text-muted-foreground">
            Select text in the document to leave a comment on it.
          </p>
        )}
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
    </div>
  )
}

function draftId(localId: string): string {
  return `draft:${localId}`
}
