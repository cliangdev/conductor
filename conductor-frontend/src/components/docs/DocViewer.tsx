'use client'

import { useMemo } from 'react'
import { CommentableDocument } from '@/components/comments/CommentableDocument'
import type { ProjectDoc } from '@/lib/docs-api'

export interface DocViewerProps {
  doc: ProjectDoc
  projectId: string
  token: string
  currentUserId: string
  currentUserName?: string
  /** Admins may delete anyone's comment. */
  isAdmin?: boolean
  /** Checkbox toggling. The owning page holds the handler, since it owns `doc`. */
  onToggleTask?: (lineNumber: number, checked: boolean) => void
  tasksReadOnly?: boolean
}

/**
 * A project doc with margin-rail comments on the unified comments resource: selection comments,
 * whole-document comments, edit, reopen and the filters. Line numbers on older comments count from the
 * raw source (frontmatter included), which is how `MarkdownRenderer` stamps its blocks, so they land on
 * the right one.
 */
export function DocViewer({
  doc,
  projectId,
  token,
  currentUserId,
  currentUserName,
  isAdmin,
  onToggleTask,
  tasksReadOnly,
}: DocViewerProps) {
  const target = useMemo(() => ({ projectId, projectDocId: doc.id }), [projectId, doc.id])
  return (
    <CommentableDocument
      content={doc.content ?? ''}
      contentHash={doc.contentHash}
      target={target}
      token={token}
      currentUserId={currentUserId}
      currentUserName={currentUserName}
      isAdmin={isAdmin}
      onToggleTask={onToggleTask}
      tasksReadOnly={tasksReadOnly}
    />
  )
}
