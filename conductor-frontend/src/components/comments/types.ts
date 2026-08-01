export interface CommentReply {
  id: string
  /** Null for a machine author (an agent or a run-scoped token) — see `authorName` for the byline. */
  authorId: string | null
  authorName: string
  content: string
  createdAt: string
}

export interface Comment {
  id: string
  documentId: string
  /** Null for a machine author (an agent or a run-scoped token) — see `authorName` for the byline. */
  authorId: string | null
  authorName: string
  content: string
  lineNumber?: number
  quotedText?: string | null
  lineStale?: boolean
  selectionStart?: number
  selectionLength?: number
  resolvedAt?: string | null
  createdAt: string
  replies: CommentReply[]
}

/**
 * A comment drafted while a batch review is in progress (COND-22 review mode) — held client-side and
 * not yet posted. Persisted to localStorage (keyed by Work Item) so a refresh doesn't lose drafts;
 * flushed to the real comments API only when the reviewer submits their verdict.
 */
export interface PendingCommentDraft {
  localId: string
  documentId: string
  lineNumber: number
  content: string
}
