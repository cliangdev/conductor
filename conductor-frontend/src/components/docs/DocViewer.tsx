'use client'

import { useState, useEffect, useCallback } from 'react'
import { CommentableDocument } from '@/components/comments/CommentableDocument'
import { getDocComments } from '@/lib/docs-api'
import type { ProjectDoc, DocComment, DocCommentReply } from '@/lib/docs-api'
import type { Comment, CommentReply } from '@/components/comments/types'

export interface DocViewerProps {
  doc: ProjectDoc
  projectId: string
  token: string
  currentUserId: string
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

export function DocViewer({ doc, projectId, token, currentUserId }: DocViewerProps) {
  const [comments, setComments] = useState<Comment[]>([])

  const fetchComments = useCallback(async () => {
    try {
      const raw = await getDocComments(projectId, doc.id, token)
      setComments(raw.map((c) => mapComment(c, doc.id)))
    } catch {
      // Non-fatal — comments unavailable should not block doc viewing
    }
  }, [projectId, doc.id, token])

  useEffect(() => {
    fetchComments()
  }, [fetchComments])

  const commentApiBasePath = `/api/v1/projects/${projectId}/docs/${doc.id}/comments`

  return (
    <CommentableDocument
      content={doc.content ?? ''}
      documentId={doc.id}
      issueId={doc.id}
      projectId={projectId}
      comments={comments}
      onCommentAdded={fetchComments}
      token={token}
      currentUserId={currentUserId}
      commentApiBasePath={commentApiBasePath}
    />
  )
}
