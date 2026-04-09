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
      <div className="text-xs text-gray-400 flex items-center gap-2 py-1">
        <span>Thread resolved</span>
        <button
          onClick={() => setShowResolved(true)}
          className="text-blue-500 hover:underline"
        >
          Show
        </button>
        {onClose && (
          <button onClick={onClose} className="ml-auto text-gray-400 hover:text-gray-600">
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
    <div className="bg-white border border-gray-200 rounded shadow-sm text-sm w-72">
      <div className="p-3 border-b border-gray-100">
        <div className="flex items-start justify-between gap-2">
          <div className="flex-1">
            <div className="flex items-center gap-1.5 mb-1">
              <span className="font-medium text-gray-800">{comment.authorName}</span>
              {comment.createdAt && (
                <span className="text-xs text-gray-400">{formatTime(comment.createdAt)}</span>
              )}
              {isResolved && (
                <span className="text-xs text-green-600 font-medium ml-1">Resolved</span>
              )}
            </div>
            <p className="text-gray-700 text-xs leading-relaxed whitespace-pre-wrap">
              {comment.content}
            </p>
          </div>
          {onClose && (
            <button
              onClick={onClose}
              className="text-gray-300 hover:text-gray-500 flex-shrink-0 text-xs leading-none"
            >
              ✕
            </button>
          )}
        </div>

        <div className="flex items-center gap-2 mt-2">
          {!isResolved && (
            <button
              onClick={handleResolve}
              className="text-xs text-green-600 hover:text-green-700 hover:underline"
            >
              Resolve
            </button>
          )}
          {isResolved && showResolved && (
            <button
              onClick={() => setShowResolved(false)}
              className="text-xs text-gray-400 hover:text-gray-600 hover:underline"
            >
              Hide resolved
            </button>
          )}
          {currentUserId === comment.authorId && (
            <button
              onClick={handleDelete}
              className="text-xs text-red-500 hover:text-red-600 hover:underline"
            >
              Delete
            </button>
          )}
          <button
            onClick={() => setShowReplyForm((v) => !v)}
            className="text-xs text-blue-500 hover:text-blue-600 hover:underline ml-auto"
          >
            Reply
          </button>
        </div>
      </div>

      {comment.replies.length > 0 && (
        <div className="px-3 py-2 border-b border-gray-100 space-y-2">
          {comment.replies.map((reply) => (
            <div key={reply.id} className="pl-2 border-l-2 border-gray-200">
              <div className="flex items-center gap-1.5 mb-0.5">
                <span className="font-medium text-gray-700 text-xs">{reply.authorName}</span>
                {reply.createdAt && (
                  <span className="text-xs text-gray-400">{formatTime(reply.createdAt)}</span>
                )}
              </div>
              <p className="text-xs text-gray-600 leading-relaxed whitespace-pre-wrap">
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
