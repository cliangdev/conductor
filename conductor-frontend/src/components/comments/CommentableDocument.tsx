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

interface LineThreadState {
  lineNumber: number
  showForm: boolean
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
  const [openLineThread, setOpenLineThread] = useState<LineThreadState | null>(null)

  const lines = content.split('\n')

  const lineComments = comments.filter((c) => c.lineNumber != null)

  function commentsForLine(lineNum: number): Comment[] {
    return lineComments.filter((c) => c.lineNumber === lineNum)
  }

  const handleAddLineComment = useCallback(
    async (lineNumber: number, text: string) => {
      await apiPost(
        `/api/v1/projects/${projectId}/issues/${issueId}/comments`,
        { documentId, content: text, lineNumber },
        token
      )
      onCommentAdded()
      setOpenLineThread(null)
    },
    [projectId, issueId, documentId, token, onCommentAdded]
  )

  return (
    <div className="flex gap-0">
      {/* Gutter — desktop only */}
      <div
        className="hidden md:block w-8 shrink-0 select-none"
        aria-label="comment gutter"
      >
        {lines.map((_, idx) => {
          const lineNum = idx + 1
          const lineHasComments = commentsForLine(lineNum).length > 0
          const isOpen = openLineThread?.lineNumber === lineNum

          return (
            <div
              key={lineNum}
              className="group/line relative flex items-center justify-center"
              style={{ height: '1.625rem' }}
            >
              {lineHasComments ? (
                <button
                  onClick={() =>
                    setOpenLineThread(isOpen ? null : { lineNumber: lineNum, showForm: false })
                  }
                  className="text-primary hover:text-primary/80 leading-none"
                  title={`${commentsForLine(lineNum).length} comment(s) on line ${lineNum}`}
                >
                  <MessageSquare className="h-3.5 w-3.5" />
                </button>
              ) : (
                <button
                  onClick={() => setOpenLineThread({ lineNumber: lineNum, showForm: true })}
                  className="opacity-0 group-hover/line:opacity-100 text-muted-foreground hover:text-foreground leading-none transition-opacity"
                  title={`Add comment on line ${lineNum}`}
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

        {/* Inline thread panel — desktop only, shown below the document */}
        {openLineThread && (
          <div className="hidden md:block mt-4 ml-2 z-30">
            <div className="flex flex-col gap-2">
              <p className="text-xs font-medium text-muted-foreground">
                Line {openLineThread.lineNumber}
              </p>
              {commentsForLine(openLineThread.lineNumber).map((c) => (
                <CommentThread
                  key={c.id}
                  comment={c}
                  projectId={projectId}
                  issueId={issueId}
                  currentUserId={currentUserId}
                  token={token}
                  onUpdated={onCommentAdded}
                  onClose={() => setOpenLineThread(null)}
                />
              ))}
              {(openLineThread.showForm ||
                commentsForLine(openLineThread.lineNumber).length === 0) && (
                <div className="bg-card border border-border rounded shadow-sm p-3 w-72">
                  <p className="text-xs text-muted-foreground mb-2">
                    New comment on line {openLineThread.lineNumber}
                  </p>
                  <NewCommentForm
                    onSubmit={(text) => handleAddLineComment(openLineThread.lineNumber, text)}
                    onCancel={() => setOpenLineThread(null)}
                  />
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      {/* All line comments — mobile accordion */}
      {lineComments.length > 0 && (
        <div className="md:hidden mt-4 border-t border-border pt-4 w-full">
          <details>
            <summary className="text-sm font-medium text-muted-foreground cursor-pointer py-1 select-none">
              {lineComments.length} comment{lineComments.length !== 1 ? 's' : ''}
            </summary>
            <div className="mt-3 space-y-3">
              {lineComments.map((c) => (
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
  )
}
