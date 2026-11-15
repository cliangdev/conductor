/** The agent behind a comment or reply, when the server knows which one. */
export interface CommentAuthorAgent {
  id: string
  name: string
  avatar?: { emoji?: string; color?: string } | null
}

export type CommentAuthorKind = 'USER' | 'AGENT'

export interface CommentReply {
  id: string
  /** Null for a machine author (an agent or a run-scoped token) — see `authorName` for the byline. */
  authorId: string | null
  authorName: string
  /** Absent on responses from the older Work Item comment routes. */
  authorKind?: CommentAuthorKind
  authorAgent?: CommentAuthorAgent | null
  content: string
  editedAt?: string | null
  createdAt: string
}

/**
 * Where a comment on an HTML document sits: the selected text plus a little context either side
 * (the W3C TextQuoteSelector model) and a hint at the section it was in. The server stores it as sent
 * and never matches text itself — the viewer re-finds the quote in the rendered page.
 */
export interface TextQuoteAnchor {
  /** The selected text, whitespace collapsed. 1–2000 characters. */
  exact: string
  /** Up to 64 characters of text immediately before the quote. */
  prefix?: string
  /** Up to 64 characters of text immediately after the quote. */
  suffix?: string
  /** `id` of the nearest ancestor element that has one (max 255). */
  sectionId?: string
  /** Text of the first heading inside that ancestor (max 500). */
  sectionTitle?: string
}

/** What a thread is anchored to; derived by the server from the anchor columns. */
export type CommentKind = 'QUOTE' | 'LINE' | 'DOCUMENT'

export interface Comment {
  id: string
  /** Null on a project-doc comment. */
  documentId: string | null
  /** Present on responses from `/comments`; absent on the older Work Item routes (derive from `anchor`/`lineNumber`). */
  kind?: CommentKind
  status?: 'OPEN' | 'RESOLVED'
  /** Null for a machine author (an agent or a run-scoped token) — see `authorName` for the byline. */
  authorId: string | null
  authorName: string
  authorKind?: CommentAuthorKind
  authorAgent?: CommentAuthorAgent | null
  editedAt?: string | null
  /** Who resolved it (a member's name, or an agent's label). */
  resolvedByName?: string | null
  content: string
  /** 0 on an anchored comment — tell anchored from line comments by `anchor`, never by this. */
  lineNumber?: number
  quotedText?: string | null
  lineStale?: boolean
  /** Present on comments made on an HTML document; null on a line comment left on a document that is now HTML. */
  anchor?: TextQuoteAnchor | null
  /** True when the document changed after the comment was written. */
  outdated?: boolean
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
  /** Markdown drafts anchor to a source line … */
  lineNumber?: number
  /** … HTML drafts to a text quote. A draft has exactly one of the two. */
  anchor?: TextQuoteAnchor
  content: string
  /**
   * `contentHash` of the document version this draft was written against, as it was displayed. Kept with
   * the (persisted) draft so one written against an older version still reports that version when it is
   * finally submitted, and the server can mark it outdated.
   */
  documentHash?: string
}
