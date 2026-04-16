'use client'

import { useState, useCallback } from 'react'
import { apiPost } from '@/lib/api'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { CommentThread } from './CommentThread'
import { NewCommentForm } from './NewCommentForm'
import type { Comment } from './types'
import { MessageSquarePlus, MessageSquare } from 'lucide-react'

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
}

interface PopoverState {
  lineNumber: number
  /** Viewport-relative position of the gutter button that was clicked */
  anchorTop: number
  anchorLeft: number
  mode: 'compose' | 'thread'
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
}: Props) {
  const [popover, setPopover] = useState<PopoverState | null>(null)

  const lines = content.split('\n')

  const LINE_HEIGHT_PX = 1.625 * 16 // 26px — matches gutter cell height

  function commentsForLine(lineNum: number): Comment[] {
    return comments.filter((c) => Number(c.lineNumber) === lineNum)
  }

  const openPopover = useCallback(
    (e: React.MouseEvent<HTMLButtonElement>, lineNum: number, mode: 'compose' | 'thread') => {
      const rect = e.currentTarget.getBoundingClientRect()
      setPopover((prev) =>
        prev?.lineNumber === lineNum && prev.mode === mode
          ? null // toggle off
          : { lineNumber: lineNum, anchorTop: rect.bottom, anchorLeft: rect.left, mode }
      )
    },
    []
  )

  const handleAddComment = useCallback(
    async (text: string) => {
      if (!popover) return
      await apiPost(
        `/api/v1/projects/${projectId}/issues/${issueId}/comments`,
        { documentId, content: text, lineNumber: popover.lineNumber },
        token
      )
      onCommentAdded()
      setPopover(null)
    },
    [popover, projectId, issueId, documentId, token, onCommentAdded]
  )

  return (
    <>
      {/* Backdrop — closes popover when clicking outside */}
      {popover && (
        <div className="fixed inset-0 z-40" onClick={() => setPopover(null)} />
      )}

      {/* Popover — anchored to the clicked gutter button */}
      {popover && (
        <div
          className="fixed z-50 bg-card border border-border rounded-lg shadow-xl p-4 w-80"
          style={{ top: popover.anchorTop + 6, left: popover.anchorLeft }}
          onClick={(e) => e.stopPropagation()}
        >
          {popover.mode === 'thread' ? (
            <>
              <div className="flex items-center justify-between mb-3">
                <p className="text-xs font-medium text-muted-foreground">
                  Line {popover.lineNumber} · {commentsForLine(popover.lineNumber).length} comment{commentsForLine(popover.lineNumber).length !== 1 ? 's' : ''}
                </p>
                <button
                  onClick={() => setPopover(null)}
                  className="text-muted-foreground hover:text-foreground text-xs"
                >
                  ✕
                </button>
              </div>
              <div className="flex flex-col gap-3 max-h-96 overflow-y-auto">
                {commentsForLine(popover.lineNumber).map((c) => (
                  <CommentThread
                    key={c.id}
                    comment={c}
                    projectId={projectId}
                    issueId={issueId}
                    currentUserId={currentUserId}
                    token={token}
                    onUpdated={() => { onCommentAdded(); setPopover(null) }}
                    onClose={() => setPopover(null)}
                  />
                ))}
              </div>
            </>
          ) : (
            <>
              <p className="text-xs font-medium text-muted-foreground mb-3">
                Comment on line {popover.lineNumber}
              </p>
              <NewCommentForm
                onSubmit={handleAddComment}
                onCancel={() => setPopover(null)}
              />
            </>
          )}
        </div>
      )}

      <div className="flex gap-0">
        {/* Gutter — desktop only */}
        <div
          className="hidden md:flex md:flex-col w-8 shrink-0 select-none"
          aria-label="comment gutter"
        >
          {lines.map((_, idx) => {
            const lineNum = idx + 1
            const lineComments = commentsForLine(lineNum)
            const hasComments = lineComments.length > 0
            const isActive = popover?.lineNumber === lineNum

            return (
              <div
                key={lineNum}
                className="group/gutterrow relative flex items-center justify-center"
                style={{ height: `${LINE_HEIGHT_PX}px` }}
              >
                {hasComments ? (
                  <button
                    onClick={(e) => openPopover(e, lineNum, 'thread')}
                    className={`leading-none transition-all text-primary ${
                      isActive ? 'scale-110' : 'hover:scale-110'
                    }`}
                    title={`${lineComments.length} comment${lineComments.length !== 1 ? 's' : ''} on line ${lineNum} — click to view`}
                  >
                    <MessageSquare className="h-3.5 w-3.5" />
                  </button>
                ) : (
                  <button
                    onClick={(e) => openPopover(e, lineNum, 'compose')}
                    className={`leading-none transition-opacity duration-150 ${
                      isActive
                        ? 'opacity-100 text-primary'
                        : 'opacity-0 group-hover/gutterrow:opacity-60 hover:!opacity-100 text-muted-foreground hover:text-primary'
                    }`}
                    title="Add comment on this line"
                  >
                    <MessageSquarePlus className="h-3.5 w-3.5" />
                  </button>
                )}
              </div>
            )
          })}
        </div>

        {/* Document content */}
        <div className="flex-1 min-w-0">
          <MarkdownRenderer content={content} onDocumentNavigate={onDocumentNavigate} />
        </div>

        {/* All line comments — mobile accordion */}
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
