'use client'

import { useState } from 'react'
import { apiPost, apiPatch, apiDelete } from '@/lib/api'
import { NewCommentForm } from './NewCommentForm'
import type { Comment } from './types'

interface Props {
  comment: Comment
  projectId: string
  issueId: string
  currentUserId: string
  token: string
  onUpdated: () => void
  onClose?: () => void
}

function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return iso
  }
}

export function CommentThread({
  comment,
  projectId,
  issueId,
  currentUserId,
  token,
  onUpdated,
  onClose,
}: Props) {
  const [showResolved, setShowResolved] = useState(false)
  const [showReplyForm, setShowReplyForm] = useState(false)

  const isResolved = !!comment.resolvedAt
  if (isResolved && !showResolved) {
    return (
      <div className="text-xs text-foreground-subtle flex items-center gap-2 py-1">
        <span>Thread resolved</span>
        <button
          onClick={() => setShowResolved(true)}
          className="text-primary hover:underline"
        >
          Show
        </button>
        {onClose && (
          <button onClick={onClose} className="ml-auto text-foreground-subtle hover:text-foreground">
            ✕
          </button>
        )}
      </div>
    )
  }

  async function handleResolve() {
    await apiPatch(
      `/api/v1/projects/${projectId}/issues/${issueId}/comments/${comment.id}/resolve`,
      {},
      token
    )
    onUpdated()
  }

  async function handleDelete() {
    if (!confirm('Delete this comment?')) return
    await apiDelete(
      `/api/v1/projects/${projectId}/issues/${issueId}/comments/${comment.id}`,
      token
    )
    onUpdated()
  }

  async function handleReply(content: string) {
    await apiPost(
      `/api/v1/projects/${projectId}/issues/${issueId}/comments/${comment.id}/replies`,
      { content },
      token
    )
    setShowReplyForm(false)
    onUpdated()
  }

  return (
    <div className="bg-card border border-border rounded shadow-sm text-sm w-72">
      <div className="p-3 border-b border-border">
        {/* Quoted text blockquote */}
        {comment.quotedText && (
          <blockquote className="border-l-4 border-muted pl-3 mb-2">
            {comment.lineStale && (
              <span className="inline-block text-xs bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300 rounded px-1.5 py-0.5 mb-1 font-medium">
                Line no longer exists
              </span>
            )}
            <p className="text-xs text-muted-foreground italic leading-relaxed line-clamp-3">
              {comment.quotedText}
            </p>
          </blockquote>
        )}
        {/* Stale indicator when there's no quotedText */}
        {comment.lineStale && !comment.quotedText && (
          <div className="mb-2">
            <span className="inline-block text-xs bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300 rounded px-1.5 py-0.5 font-medium">
              Line no longer exists
            </span>
          </div>
        )}
        <div className="flex items-start justify-between gap-2">
          <div className="flex-1">
            <div className="flex items-center gap-1.5 mb-1 flex-wrap">
              <span className="font-medium text-foreground">{comment.authorName}</span>
              {comment.createdAt && (
                <span className="text-xs text-foreground-subtle">{formatTime(comment.createdAt)}</span>
              )}
              {isResolved && (
                <span className="text-xs text-status-approved font-medium ml-1">Resolved</span>
              )}
            </div>
            <p className="text-foreground text-xs leading-relaxed whitespace-pre-wrap">
              {comment.content}
            </p>
          </div>
          {onClose && (
            <button
              onClick={onClose}
              className="text-foreground-subtle hover:text-foreground flex-shrink-0 text-xs leading-none transition-colors"
            >
              ✕
            </button>
          )}
        </div>

        <div className="flex items-center gap-2 mt-2">
          {!isResolved && (
            <button
              onClick={handleResolve}
              className="text-xs text-status-approved hover:underline transition-colors"
            >
              Resolve
            </button>
          )}
          {isResolved && showResolved && (
            <button
              onClick={() => setShowResolved(false)}
              className="text-xs text-foreground-subtle hover:text-foreground hover:underline transition-colors"
            >
              Hide resolved
            </button>
          )}
          {currentUserId === comment.authorId && (
            <button
              onClick={handleDelete}
              className="text-xs text-destructive hover:text-destructive/80 hover:underline transition-colors"
            >
              Delete
            </button>
          )}
          <button
            onClick={() => setShowReplyForm((v) => !v)}
            className="text-xs text-primary hover:text-primary/80 hover:underline ml-auto transition-colors"
          >
            Reply
          </button>
        </div>
      </div>

      {comment.replies.length > 0 && (
        <div className="px-3 py-2 border-b border-border space-y-2">
          {comment.replies.map((reply) => (
            <div key={reply.id} className="pl-2 border-l-2 border-border">
              <div className="flex items-center gap-1.5 mb-0.5">
                <span className="font-medium text-foreground text-xs">{reply.authorName}</span>
                {reply.createdAt && (
                  <span className="text-xs text-foreground-subtle">{formatTime(reply.createdAt)}</span>
                )}
              </div>
              <p className="text-xs text-muted-foreground leading-relaxed whitespace-pre-wrap">
                {reply.content}
              </p>
            </div>
          ))}
        </div>
      )}

      {showReplyForm && (
        <div className="p-3">
          <NewCommentForm
            onSubmit={handleReply}
            onCancel={() => setShowReplyForm(false)}
            placeholder="Write a reply..."
            submitLabel="Reply"
          />
        </div>
      )}
    </div>
  )
}
