'use client'

import { useCallback, useMemo, useRef, useState } from 'react'
import { apiPost } from '@/lib/api'
import { cn } from '@/lib/utils'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { Badge } from '@/components/ui/badge'
import { CommentThread } from './CommentThread'
import { NewCommentForm } from './NewCommentForm'
import type { Comment, PendingCommentDraft } from './types'
import {
  fallbackBox,
  snapToRendered,
  useSourceLineOffsets,
  type LineBox,
} from './useSourceLineOffsets'
import { MessageSquarePlus, PencilIcon, TrashIcon, XIcon } from 'lucide-react'

interface Props {
  content: string
  documentId: string
  issueId: string
  projectId: string
  comments: Comment[]
  onCommentAdded: () => void
  token: string
  currentUserId: string
  onDocumentNavigate?: (filename: string) => void
  /** Override the base path for comment API calls (e.g. for doc comments). Defaults to issue comment path. */
  commentApiBasePath?: string
  /**
   * Batch review mode (COND-22): when true, a new line comment is held client-side via
   * `onAddPendingComment` instead of posted immediately. Defaults to the normal immediate-post
   * behavior when omitted (Knowledge Center's DocViewer never sets this).
   */
  reviewMode?: boolean
  /** All of the Work Item's pending drafts — filtered internally to this component's `documentId`. */
  pendingComments?: PendingCommentDraft[]
  onAddPendingComment?: (lineNumber: number, content: string) => void
  onEditPendingComment?: (localId: string, content: string) => void
  onRemovePendingComment?: (localId: string) => void
  /**
   * Opt in to interactive GFM checkboxes (see `MarkdownRenderer`). The gutter needs no adjustment:
   * `[ ]` and `[x]` are the same width, so toggling never moves a line and comment anchors hold.
   */
  onToggleTask?: (lineNumber: number, checked: boolean) => void
  tasksReadOnly?: boolean
}

interface PopoverState {
  lineNumber: number
  mode: 'compose' | 'thread'
  /** Decided once at open time (near the viewport bottom) — the popover then stays put as the page scrolls. */
  flip: boolean
}

export function CommentableDocument({
  content,
  documentId,
  issueId,
  projectId,
  comments,
  onCommentAdded,
  token,
  currentUserId,
  onDocumentNavigate,
  commentApiBasePath,
  reviewMode = false,
  pendingComments = [],
  onAddPendingComment,
  onEditPendingComment,
  onRemovePendingComment,
  onToggleTask,
  tasksReadOnly,
}: Props) {
  const basePath =
    commentApiBasePath ?? `/api/v2/projects/${projectId}/work-items/${issueId}/comments`
  const [popover, setPopover] = useState<PopoverState | null>(null)
  const [editingPendingId, setEditingPendingId] = useState<string | null>(null)

  const contentRef = useRef<HTMLDivElement>(null)
  const measured = useSourceLineOffsets(contentRef, content)

  const lines = content.split('\n')
  const docPending = pendingComments.filter((p) => p.documentId === documentId)

  /** Measured position of a source line, or the old uniform-height guess before measurement lands. */
  const boxFor = useCallback(
    (lineNum: number): LineBox => measured?.get(lineNum) ?? fallbackBox(lineNum),
    [measured]
  )

  /**
   * A comment's own line may not render anything of its own — it can sit on a blank separator, or
   * inside a block whose narrowest anchor starts earlier. Snapping to the nearest line that *did*
   * render keeps such a comment reachable instead of stranding it at a position nothing occupies.
   * Identity when unmeasured, so the pre-measurement pass behaves exactly as before.
   */
  const resolveLine = useCallback(
    (lineNum: number): number => (measured ? snapToRendered(lineNum, measured) : lineNum),
    [measured]
  )

  /**
   * Which lines get a gutter row. Once measured, only lines that actually render something do — blank
   * separators and frontmatter stop claiming rows they never lined up with anyway.
   */
  const gutterLines = useMemo(() => {
    if (!measured) return lines.map((_, idx) => idx + 1)
    return [...measured.keys()].sort((a, b) => a - b)
  }, [measured, lines])

  const commentsForLine = useCallback(
    (lineNum: number): Comment[] =>
      comments.filter((c) => c.lineNumber != null && resolveLine(Number(c.lineNumber)) === lineNum),
    [comments, resolveLine]
  )

  const pendingForLine = useCallback(
    (lineNum: number): PendingCommentDraft[] =>
      docPending.filter((p) => resolveLine(p.lineNumber) === lineNum),
    [docPending, resolveLine]
  )

  const openPopover = useCallback(
    (e: React.MouseEvent<HTMLButtonElement>, lineNum: number, mode: 'compose' | 'thread') => {
      // One-shot decision, not stored position: near the visible viewport bottom, open upward instead
      // of off-screen. The popover's actual top/bottom offset below is still derived purely from the
      // line number, so it stays correctly placed as the page scrolls after opening.
      const rect = e.currentTarget.getBoundingClientRect()
      const estimatedPopoverHeight = 220
      const flip = window.innerHeight - rect.bottom < estimatedPopoverHeight
      setPopover((prev) =>
        prev?.lineNumber === lineNum && prev.mode === mode
          ? null // toggle off
          : { lineNumber: lineNum, mode, flip }
      )
    },
    []
  )

  const handleAddComment = useCallback(
    async (text: string) => {
      if (!popover) return
      if (reviewMode) {
        onAddPendingComment?.(popover.lineNumber, text)
      } else {
        await apiPost(
          basePath,
          { documentId, content: text, lineNumber: popover.lineNumber },
          token
        )
        onCommentAdded()
      }
      setPopover(null)
    },
    [popover, reviewMode, onAddPendingComment, basePath, documentId, token, onCommentAdded]
  )

  return (
    <>
      {/* Backdrop — closes popover when clicking outside */}
      {popover && <div className="fixed inset-0 z-40" onClick={() => setPopover(null)} />}

      <div className="relative flex gap-0">
        {/* Popover — positioned within this relative container (scrolls with the content instead of
            desyncing like a viewport-fixed popover would). */}
        {popover && (
          <div
            data-testid="comment-popover"
            className="absolute left-8 z-50 w-96 max-w-[min(24rem,calc(100%-2rem))] bg-card border border-border rounded-lg shadow-xl p-4"
            style={
              popover.flip
                ? {
                    // Anchored to the gutter row's own top offset, then shifted up by the popover's
                    // own rendered height — not the container's total height, which would misplace it.
                    top: `${boxFor(popover.lineNumber).top - 6}px`,
                    transform: 'translateY(-100%)',
                  }
                : {
                    top: `${
                      boxFor(popover.lineNumber).top + boxFor(popover.lineNumber).height + 6
                    }px`,
                  }
            }
            onClick={(e) => e.stopPropagation()}
          >
            {popover.mode === 'thread' ? (
              <ThreadPopoverContent
                lineNumber={popover.lineNumber}
                existing={commentsForLine(popover.lineNumber)}
                pending={pendingForLine(popover.lineNumber)}
                projectId={projectId}
                issueId={issueId}
                currentUserId={currentUserId}
                token={token}
                onUpdated={() => {
                  onCommentAdded()
                  setPopover(null)
                }}
                onClose={() => setPopover(null)}
                commentApiBasePath={commentApiBasePath}
                editingPendingId={editingPendingId}
                setEditingPendingId={setEditingPendingId}
                onEditPendingComment={onEditPendingComment}
                onRemovePendingComment={onRemovePendingComment}
              />
            ) : (
              <>
                <p className="text-xs font-medium text-muted-foreground mb-3">
                  Comment on line {popover.lineNumber}
                </p>
                <NewCommentForm
                  onSubmit={handleAddComment}
                  onCancel={() => setPopover(null)}
                  size="comfortable"
                />
              </>
            )}
          </div>
        )}

        {/* Gutter — desktop only. Rows are absolutely positioned at each line's *measured* offset
            rather than stacked at a uniform height, so a marker stays beside its line no matter what
            the document contains. `relative` + a min-height keeps the column sized while the rows
            themselves are taken out of flow. */}
        <div
          className="relative hidden md:block w-8 shrink-0 select-none"
          aria-label="comment gutter"
        >
          {gutterLines.map((lineNum) => {
            const box = boxFor(lineNum)
            const lineComments = commentsForLine(lineNum)
            const linePending = pendingForLine(lineNum)
            const totalCount = lineComments.length + linePending.length
            const hasComments = totalCount > 0
            const isActive = popover?.lineNumber === lineNum
            // Nothing posted yet — this line only holds the reviewer's own unsubmitted drafts.
            const pendingOnly = hasComments && lineComments.length === 0
            const label = `${totalCount} ${pendingOnly ? 'pending ' : ''}comment${
              totalCount !== 1 ? 's' : ''
            } on line ${lineNum}`

            return (
              <div
                key={lineNum}
                data-line={lineNum}
                // items-start, not items-center: a tall block (an image, a long code fence) gets one
                // tall row, and centring inside it would float the marker into the middle of the
                // block instead of beside the line it belongs to.
                className={cn(
                  'group/gutterrow flex items-start justify-center',
                  measured ? 'absolute inset-x-0' : 'relative'
                )}
                style={
                  measured
                    ? { top: `${box.top}px`, height: `${box.height}px` }
                    : { height: `${box.height}px` }
                }
              >
                {hasComments ? (
                  <button
                    onClick={(e) => openPopover(e, lineNum, 'thread')}
                    className={cn(
                      // mt centres the 20px marker within a normal 26px line box.
                      'mt-[3px] flex h-5 min-w-5 items-center justify-center rounded-full px-1',
                      'text-[11px] font-medium leading-none ring-1 transition-colors',
                      // `text-accent` used to paint this: in this codebase --accent is the legacy
                      // shadcn neutral (near-black in dark mode), not the teal accent. --primary is.
                      isActive
                        ? 'bg-primary text-primary-foreground ring-primary'
                        : 'bg-accent-soft text-primary ring-primary/40 hover:bg-primary/20',
                      // Drafts read as provisional until the review is submitted.
                      pendingOnly && !isActive && 'border border-dashed border-primary/60 ring-0'
                    )}
                    title={`${label} — click to view`}
                    aria-label={label}
                  >
                    {totalCount}
                  </button>
                ) : (
                  <button
                    onClick={(e) => openPopover(e, lineNum, 'compose')}
                    className={cn(
                      'mt-[2px] rounded p-0.5 leading-none transition-opacity duration-150 hover:bg-muted',
                      isActive
                        ? 'bg-muted text-primary opacity-100'
                        : 'text-muted-foreground opacity-0 group-hover/gutterrow:opacity-100 hover:text-primary'
                    )}
                    title="Add comment on this line"
                  >
                    <MessageSquarePlus className="h-4 w-4" />
                  </button>
                )}
              </div>
            )
          })}
        </div>

        {/* Document content */}
        <div ref={contentRef} className="flex-1 min-w-0">
          <MarkdownRenderer
            content={content}
            onDocumentNavigate={onDocumentNavigate}
            onToggleTask={onToggleTask}
            tasksReadOnly={tasksReadOnly}
            annotateSourceLines
          />
        </div>

        {/* All line comments — mobile accordion (pending drafts aren't editable here; review mode is desktop-only, matching the gutter itself) */}
        {comments.filter((c) => c.lineNumber != null).length > 0 && (
          <div className="md:hidden mt-4 border-t border-border pt-4 w-full">
            <details>
              <summary className="text-sm font-medium text-muted-foreground cursor-pointer py-1 select-none">
                {comments.filter((c) => c.lineNumber != null).length} comment
                {comments.filter((c) => c.lineNumber != null).length !== 1 ? 's' : ''}
              </summary>
              <div className="mt-3 space-y-3">
                {comments
                  .filter((c) => c.lineNumber != null)
                  .map((c) => (
                    <CommentThread
                      key={c.id}
                      comment={c}
                      projectId={projectId}
                      issueId={issueId}
                      currentUserId={currentUserId}
                      token={token}
                      onUpdated={onCommentAdded}
                      commentApiBasePath={commentApiBasePath}
                    />
                  ))}
              </div>
            </details>
          </div>
        )}
      </div>
    </>
  )
}

/** A single held-locally, not-yet-posted comment — editable/deletable until the review is submitted. */
function PendingCommentRow({
  draft,
  editing,
  onStartEdit,
  onCancelEdit,
  onSave,
  onRemove,
}: {
  draft: PendingCommentDraft
  editing: boolean
  onStartEdit: () => void
  onCancelEdit: () => void
  onSave: (content: string) => void
  onRemove: () => void
}) {
  if (editing) {
    return (
      <div className="border border-border rounded p-2 bg-card">
        <NewCommentForm
          onSubmit={async (text) => onSave(text)}
          onCancel={onCancelEdit}
          size="compact"
          submitLabel="Save"
          initialValue={draft.content}
        />
      </div>
    )
  }

  return (
    <div className="border border-border rounded p-2 bg-card text-xs">
      <div className="flex items-center justify-between gap-2 mb-1">
        <Badge variant="outline" className="text-[10px]">
          Pending
        </Badge>
        <div className="flex items-center gap-1 shrink-0">
          <button
            onClick={onStartEdit}
            aria-label="Edit pending comment"
            className="p-0.5 text-muted-foreground hover:text-foreground transition-colors"
          >
            <PencilIcon className="h-3 w-3" />
          </button>
          <button
            onClick={onRemove}
            aria-label="Remove pending comment"
            className="p-0.5 text-destructive hover:text-destructive/80 transition-colors"
          >
            <TrashIcon className="h-3 w-3" />
          </button>
        </div>
      </div>
      <p className="text-foreground whitespace-pre-wrap leading-relaxed">{draft.content}</p>
    </div>
  )
}

function ThreadPopoverContent({
  lineNumber,
  existing,
  pending,
  projectId,
  issueId,
  currentUserId,
  token,
  onUpdated,
  onClose,
  commentApiBasePath,
  editingPendingId,
  setEditingPendingId,
  onEditPendingComment,
  onRemovePendingComment,
}: {
  lineNumber: number
  existing: Comment[]
  pending: PendingCommentDraft[]
  projectId: string
  issueId: string
  currentUserId: string
  token: string
  onUpdated: () => void
  onClose: () => void
  commentApiBasePath?: string
  editingPendingId: string | null
  setEditingPendingId: (id: string | null) => void
  onEditPendingComment?: (localId: string, content: string) => void
  onRemovePendingComment?: (localId: string) => void
}) {
  const total = existing.length + pending.length
  return (
    <>
      <div className="flex items-center justify-between mb-3">
        <p className="text-xs font-medium text-muted-foreground">
          Line {lineNumber} · {total} comment{total !== 1 ? 's' : ''}
        </p>
        <button onClick={onClose} className="text-muted-foreground hover:text-foreground">
          <XIcon className="h-3.5 w-3.5" />
        </button>
      </div>
      <div className="flex flex-col gap-3 max-h-96 overflow-y-auto">
        {existing.map((c) => (
          <CommentThread
            key={c.id}
            comment={c}
            projectId={projectId}
            issueId={issueId}
            currentUserId={currentUserId}
            token={token}
            onUpdated={onUpdated}
            onClose={onClose}
            commentApiBasePath={commentApiBasePath}
          />
        ))}
        {pending.map((p) => (
          <PendingCommentRow
            key={p.localId}
            draft={p}
            editing={editingPendingId === p.localId}
            onStartEdit={() => setEditingPendingId(p.localId)}
            onCancelEdit={() => setEditingPendingId(null)}
            onSave={(text) => {
              onEditPendingComment?.(p.localId, text)
              setEditingPendingId(null)
            }}
            onRemove={() => onRemovePendingComment?.(p.localId)}
          />
        ))}
      </div>
    </>
  )
}
