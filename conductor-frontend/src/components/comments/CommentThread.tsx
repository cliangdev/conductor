'use client'

import { useState } from 'react'
import { XIcon } from 'lucide-react'
import { apiPost, apiPatch, apiDelete, apiErrorMessage } from '@/lib/api'
import { toastError } from '@/components/ui/toast'
import { Modal } from '@/components/ui/modal'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
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
  /** Override the base path for comment API calls (e.g. for doc comments). Defaults to issue comment path. */
  commentApiBasePath?: string
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
  commentApiBasePath,
}: Props) {
  const basePath =
    commentApiBasePath ?? `/api/v2/projects/${projectId}/work-items/${issueId}/comments`
  const [showResolved, setShowResolved] = useState(false)
  const [showReplyForm, setShowReplyForm] = useState(false)
  const [confirmDeleteOpen, setConfirmDeleteOpen] = useState(false)

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
            <XIcon className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
    )
  }

  async function handleResolve() {
    try {
      await apiPatch(`${basePath}/${comment.id}/resolve`, {}, token)
      onUpdated()
    } catch (err) {
      toastError(apiErrorMessage(err, 'Failed to resolve comment'))
    }
  }

  async function handleDelete() {
    setConfirmDeleteOpen(false)
    try {
      await apiDelete(`${basePath}/${comment.id}`, token)
      onUpdated()
    } catch (err) {
      toastError(apiErrorMessage(err, 'Failed to delete comment'))
    }
  }

  async function handleReply(content: string) {
    await apiPost(`${basePath}/${comment.id}/replies`, { content }, token)
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
              <Badge variant="status-progress" className="mb-1">
                Line no longer exists
              </Badge>
            )}
            <p className="text-xs text-muted-foreground italic leading-relaxed line-clamp-3">
              {comment.quotedText}
            </p>
          </blockquote>
        )}
        {/* Stale indicator when there's no quotedText */}
        {comment.lineStale && !comment.quotedText && (
          <div className="mb-2">
            <Badge variant="status-progress">Line no longer exists</Badge>
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
              className="text-foreground-subtle hover:text-foreground flex-shrink-0 leading-none transition-colors"
            >
              <XIcon className="h-3.5 w-3.5" />
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
              onClick={() => setConfirmDeleteOpen(true)}
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

      <Modal
        open={confirmDeleteOpen}
        onOpenChange={setConfirmDeleteOpen}
        title="Delete comment?"
        description="This can't be undone."
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setConfirmDeleteOpen(false)}>
              Cancel
            </Button>
            <Button variant="destructive" size="sm" onClick={handleDelete}>
              Delete
            </Button>
          </div>
        }
      >
        <p className="text-sm text-muted-foreground">
          {comment.content.length > 120 ? `${comment.content.slice(0, 120)}…` : comment.content}
        </p>
      </Modal>
    </div>
  )
}
