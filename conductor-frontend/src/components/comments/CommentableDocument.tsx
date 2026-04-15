'use client'

import { useState, useCallback, useRef } from 'react'
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

interface ComposeState {
  lineNumber: number
  /** Viewport-relative position of the gutter button that was clicked */
  anchorTop: number
  anchorLeft: number
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
  const [composeState, setComposeState] = useState<ComposeState | null>(null)
  const [openThreadLine, setOpenThreadLine] = useState<number | null>(null)
  const [hoveredLine, setHoveredLine] = useState<number | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  const lines = content.split('\n')

  function commentsForLine(lineNum: number): Comment[] {
    return comments.filter((c) => c.lineNumber === lineNum)
  }

  const handleGutterClick = useCallback(
    (e: React.MouseEvent<HTMLButtonElement>, lineNum: number) => {
      const rect = e.currentTarget.getBoundingClientRect()
      setComposeState({
        lineNumber: lineNum,
        anchorTop: rect.bottom,
        anchorLeft: rect.left,
      })
      setOpenThreadLine(null)
    },
    []
  )

  const handleThreadClick = useCallback((lineNum: number) => {
    setOpenThreadLine((prev) => (prev === lineNum ? null : lineNum))
    setComposeState(null)
  }, [])

  const handleAddComment = useCallback(
    async (text: string) => {
      if (!composeState) return
      await apiPost(
        `/api/v1/projects/${projectId}/issues/${issueId}/comments`,
        { documentId, content: text, lineNumber: composeState.lineNumber },
        token
      )
      onCommentAdded()
      setComposeState(null)
    },
    [composeState, projectId, issueId, documentId, token, onCommentAdded]
  )

  const LINE_HEIGHT_REM = 1.625 // matches the gutter cell height

  return (
    <>
      {/* Backdrop — closes compose popover when clicking outside */}
      {composeState && (
        <div
          className="fixed inset-0 z-40"
          onClick={() => setComposeState(null)}
        />
      )}

      {/* Compose popover — anchored to the gutter button */}
      {composeState && (
        <div
          className="fixed z-50 bg-card border border-border rounded-lg shadow-xl p-4 w-80"
          style={{
            top: composeState.anchorTop + 6,
            left: composeState.anchorLeft,
          }}
          onClick={(e) => e.stopPropagation()}
        >
          <p className="text-xs font-medium text-muted-foreground mb-3">
            Comment on line {composeState.lineNumber}
          </p>
          <NewCommentForm
            onSubmit={handleAddComment}
            onCancel={() => setComposeState(null)}
          />
        </div>
      )}

      <div className="flex gap-0" ref={containerRef}>
        {/* Gutter — desktop only */}
        <div
          className="hidden md:block w-8 shrink-0 select-none"
          aria-label="comment gutter"
        >
          {lines.map((_, idx) => {
            const lineNum = idx + 1
            const lineComments = commentsForLine(lineNum)
            const hasComments = lineComments.length > 0
            const isHovered = hoveredLine === lineNum
            const isComposeOpen = composeState?.lineNumber === lineNum
            const isThreadOpen = openThreadLine === lineNum

            return (
              <div
                key={lineNum}
                className="relative flex items-center justify-center"
                style={{ height: `${LINE_HEIGHT_REM}rem` }}
              >
                {hasComments ? (
                  <button
                    onClick={() => handleThreadClick(lineNum)}
                    className={`text-primary leading-none transition-all ${
                      isThreadOpen ? 'scale-110' : 'hover:scale-110'
                    }`}
                    title={`${lineComments.length} comment${lineComments.length !== 1 ? 's' : ''} on line ${lineNum} — click to view`}
                  >
                    <MessageSquare className="h-3.5 w-3.5" />
                  </button>
                ) : (
                  <button
                    onClick={(e) => handleGutterClick(e, lineNum)}
                    className={`leading-none transition-all duration-150 ${
                      isHovered || isComposeOpen
                        ? 'opacity-100 text-primary scale-110'
                        : 'opacity-0 text-muted-foreground'
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

        {/* Document content with line hover overlay */}
        <div className="flex-1 min-w-0 relative">
          {/* Transparent per-line hover strips — same height as gutter rows */}
          <div
            className="absolute inset-0 pointer-events-none z-10"
            aria-hidden="true"
          >
            {lines.map((_, idx) => {
              const lineNum = idx + 1
              const isHovered = hoveredLine === lineNum
              const isActive = composeState?.lineNumber === lineNum || openThreadLine === lineNum
              return (
                <div
                  key={lineNum}
                  className={`pointer-events-auto transition-colors duration-100 ${
                    isActive
                      ? 'bg-primary/8 border-l-2 border-primary'
                      : isHovered
                        ? 'bg-muted/40 border-l-2 border-border'
                        : ''
                  }`}
                  style={{ height: `${LINE_HEIGHT_REM}rem` }}
                  onMouseEnter={() => setHoveredLine(lineNum)}
                  onMouseLeave={() => setHoveredLine(null)}
                />
              )
            })}
          </div>

          <MarkdownRenderer content={content} onDocumentNavigate={onDocumentNavigate} />

          {/* Inline thread panel */}
          {openThreadLine !== null && commentsForLine(openThreadLine).length > 0 && (
            <div className="hidden md:block mt-4 ml-2">
              <div className="flex flex-col gap-2">
                <p className="text-xs font-medium text-muted-foreground">
                  Line {openThreadLine} · {commentsForLine(openThreadLine).length} comment{commentsForLine(openThreadLine).length !== 1 ? 's' : ''}
                </p>
                {commentsForLine(openThreadLine).map((c) => (
                  <CommentThread
                    key={c.id}
                    comment={c}
                    projectId={projectId}
                    issueId={issueId}
                    currentUserId={currentUserId}
                    token={token}
                    onUpdated={onCommentAdded}
                    onClose={() => setOpenThreadLine(null)}
                  />
                ))}
              </div>
            </div>
          )}
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
