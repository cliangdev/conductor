'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { CommentableDocument } from '@/components/comments/CommentableDocument'
import { getDocComments } from '@/lib/docs-api'
import { apiErrorMessage } from '@/lib/api'
import { toastError } from '@/components/ui/toast'
import type { ProjectDoc, DocComment, DocCommentReply } from '@/lib/docs-api'
import type { Comment, CommentReply } from '@/components/comments/types'

export interface DocViewerProps {
  doc: ProjectDoc
  projectId: string
  token: string
  currentUserId: string
  /** Checkbox toggling. The owning page holds the handler, since it owns `doc`. */
  onToggleTask?: (lineNumber: number, checked: boolean) => void
  tasksReadOnly?: boolean
  /**
   * The doc's comments request, already in flight (the owning page starts it alongside the doc fetch
   * so the comments don't wait for the content to render). Used for the first load only.
   */
  initialComments?: Promise<DocComment[]>
}

function mapReply(reply: DocCommentReply): CommentReply {
  return {
    id: reply.id,
    authorId: reply.authorId,
    authorName: reply.authorName,
    content: reply.content,
    createdAt: reply.createdAt,
  }
}

function mapComment(docComment: DocComment, docId: string): Comment {
  return {
    id: docComment.id,
    documentId: docId,
    authorId: docComment.authorId,
    authorName: docComment.authorName,
    content: docComment.content,
    lineNumber: docComment.lineNumber ?? undefined,
    quotedText: docComment.quotedText,
    lineStale: docComment.lineStale,
    resolvedAt: docComment.resolvedAt,
    createdAt: docComment.createdAt,
    replies: docComment.replies.map(mapReply),
  }
}

export function DocViewer({
  doc,
  projectId,
  token,
  currentUserId,
  onToggleTask,
  tasksReadOnly,
  initialComments,
}: DocViewerProps) {
  const [comments, setComments] = useState<Comment[]>([])

  const fetchComments = useCallback(async (request?: Promise<DocComment[]>) => {
    try {
      const raw = await (request ?? getDocComments(projectId, doc.id, token))
      setComments(raw.map((c) => mapComment(c, doc.id)))
    } catch (err) {
      // Still non-fatal — an unreachable comments API shouldn't stop you reading the doc. But it is no
      // longer *silent*: swallowing this hid a server-side 500 that made comments invisible on every
      // doc, with nothing on screen to suggest anything was missing.
      console.error('Failed to load doc comments', err)
      toastError(apiErrorMessage(err, 'Comments could not be loaded'))
    }
  }, [projectId, doc.id, token])

  // The first load adopts the request the page already started; later reloads (a comment added) re-ask.
  const initialRef = useRef(initialComments)
  useEffect(() => {
    const request = initialRef.current
    initialRef.current = undefined
    void fetchComments(request)
  }, [fetchComments])

  const commentApiBasePath = `/api/v1/projects/${projectId}/docs/${doc.id}/comments`

  return (
    <CommentableDocument
      content={doc.content ?? ''}
      documentId={doc.id}
      issueId={doc.id}
      projectId={projectId}
      comments={comments}
      onCommentAdded={() => fetchComments()}
      token={token}
      currentUserId={currentUserId}
      commentApiBasePath={commentApiBasePath}
      onToggleTask={onToggleTask}
      tasksReadOnly={tasksReadOnly}
    />
  )
}
