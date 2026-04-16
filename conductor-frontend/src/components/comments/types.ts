export interface CommentReply {
  id: string
  authorId: string
  authorName: string
  content: string
  createdAt: string
}

export interface Comment {
  id: string
  documentId: string
  authorId: string
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
