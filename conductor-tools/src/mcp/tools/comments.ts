import { Config } from '../config.js'
import { apiGet, apiPost, apiPatch } from '../api.js'

/**
 * Comment tools — one model for every commentable document: a Work Item document (addressed by
 * `issueId` + `filename`) or a project doc (addressed by `docId`). Backed by the unified
 * `/api/v2/projects/{p}/comments` resource. See docs/comments.md.
 *
 * Output is deliberately compact: an agent works through every open thread in one list call, so each
 * thread is trimmed to what it needs to decide (where, who, what was said, replies).
 */

const MAX_QUOTE_CHARS = 2000
const QUOTE_PREVIEW_CHARS = 200

export interface CommentTarget {
  issueId?: string
  filename?: string
  docId?: string
}

interface ThreadReply {
  authorName?: string | null
  authorKind?: string
  content: string
}

interface Thread {
  id: string
  kind: string
  status: string
  documentId?: string | null
  documentName?: string | null
  authorName?: string | null
  authorKind?: string
  content: string
  lineNumber?: number | null
  quotedText?: string | null
  anchor?: { exact: string } | null
  anchorStatus?: string | null
  suggestion?: { text: string; status: string } | null
  outdated?: boolean
  lineStale?: boolean
  replies?: ThreadReply[]
}

interface DocumentRow {
  id: string
  filename: string
  contentType?: string
  content?: string | null
  contentHash?: string | null
}

function commentsBase(config: Config): string {
  return `/api/v2/projects/${config.projectId}/comments`
}

function documentsBase(issueId: string, config: Config): string {
  return `/api/v2/projects/${config.projectId}/work-items/${issueId}/documents`
}

/** Finds a Work Item document by filename, with a message that names the real filenames on a miss. */
async function findDocument(issueId: string, filename: string, config: Config): Promise<DocumentRow> {
  const docs = await apiGet<DocumentRow[]>(documentsBase(issueId, config), config)
  const rows = Array.isArray(docs) ? docs : []
  const match = rows.find((d) => d.filename === filename)
  if (!match) {
    const known = rows.map((d) => d.filename).join(', ')
    throw new Error(
      `No document "${filename}" on Work Item ${issueId}${known ? ` — available: ${known}` : ''}`
    )
  }
  return match
}

function requireTarget(target: CommentTarget): void {
  if (target.docId) return
  if (target.issueId) return
  throw new Error('Provide issueId (with filename) for a Work Item document, or docId for a project doc')
}

function requireIssueFilename(target: CommentTarget): { issueId: string; filename: string } {
  if (!target.issueId || !target.filename) {
    throw new Error('Provide issueId and filename for a Work Item document, or docId for a project doc')
  }
  return { issueId: target.issueId, filename: target.filename }
}

function compactThread(t: Thread): Record<string, unknown> {
  const out: Record<string, unknown> = {
    id: t.id,
    kind: t.kind,
    author: t.authorName ? `${t.authorName}${t.authorKind === 'AGENT' ? ' (agent)' : ''}` : undefined,
    body: t.content,
    resolved: t.status === 'RESOLVED',
  }
  if (t.documentName) out['document'] = t.documentName
  if (t.kind === 'QUOTE') {
    const quote = t.anchor?.exact ?? t.quotedText ?? ''
    out['quote'] =
      quote.length > QUOTE_PREVIEW_CHARS ? `${quote.slice(0, QUOTE_PREVIEW_CHARS)}…` : quote
  } else if (t.kind === 'LINE') {
    out['line'] = t.lineNumber
    if (t.quotedText) {
      out['lineText'] =
        t.quotedText.length > QUOTE_PREVIEW_CHARS
          ? `${t.quotedText.slice(0, QUOTE_PREVIEW_CHARS)}…`
          : t.quotedText
    }
  }
  if (t.anchorStatus) out['anchorStatus'] = t.anchorStatus
  if (t.suggestion) out['suggestion'] = { text: t.suggestion.text, status: t.suggestion.status }
  if (t.lineStale || t.outdated) out['stale'] = true
  const replies = (t.replies ?? []).map((r) => ({
    author: r.authorName ? `${r.authorName}${r.authorKind === 'AGENT' ? ' (agent)' : ''}` : undefined,
    body: r.content,
  }))
  if (replies.length > 0) out['replies'] = replies
  return out
}

export async function listComments(
  params: CommentTarget & { status?: 'open' | 'resolved' | 'all' },
  config: Config
): Promise<Record<string, unknown>> {
  requireTarget(params)
  const status = params.status ?? 'open'
  const query = new URLSearchParams()
  if (params.docId) {
    query.set('projectDocId', params.docId)
  } else {
    query.set('workItemId', params.issueId as string)
    if (params.filename) {
      const doc = await findDocument(params.issueId as string, params.filename, config)
      query.set('documentId', doc.id)
    }
  }
  query.set('status', status)

  const threads = await apiGet<Thread[]>(`${commentsBase(config)}?${query.toString()}`, config)
  const rows = Array.isArray(threads) ? threads : []
  return { count: rows.length, status, threads: rows.map(compactThread) }
}

export async function addComment(
  params: CommentTarget & { body: string; quote?: string; suggestion?: string },
  config: Config
): Promise<Record<string, unknown>> {
  requireTarget(params)
  if (!params.body || !params.body.trim()) {
    throw new Error('body is required')
  }
  if (params.quote !== undefined && params.quote.length > MAX_QUOTE_CHARS) {
    throw new Error(`quote is too long (max ${MAX_QUOTE_CHARS} characters) — quote a shorter passage`)
  }

  if (params.suggestion !== undefined && !params.quote) {
    throw new Error('suggestion needs quote — the exact text it would replace')
  }

  let target: Record<string, string>
  if (params.docId) {
    target = { projectDocId: params.docId }
  } else {
    const { issueId, filename } = requireIssueFilename(params)
    const doc = await findDocument(issueId, filename, config)
    target = { workItemId: issueId, documentId: doc.id }
  }

  const body: Record<string, unknown> = { target, content: params.body }
  if (params.quote) {
    body['anchor'] = { exact: params.quote }
  }
  if (params.suggestion !== undefined) {
    body['suggestion'] = { text: params.suggestion }
  }

  const created = await apiPost<Thread>(commentsBase(config), body, config)
  return compactThread(created)
}

export async function respondToComment(
  params: { commentId: string; reply?: string; resolve?: boolean; reopen?: boolean },
  config: Config
): Promise<Record<string, unknown>> {
  const hasReply = typeof params.reply === 'string' && params.reply.trim().length > 0
  if (params.resolve && params.reopen) {
    throw new Error('Pass resolve or reopen, not both')
  }
  if (!hasReply && !params.resolve && !params.reopen) {
    throw new Error('Provide reply, resolve: true, reopen: true, or a combination')
  }

  const base = `${commentsBase(config)}/${params.commentId}`
  const result: Record<string, unknown> = { commentId: params.commentId }

  if (hasReply) {
    await apiPost(`${base}/replies`, { content: params.reply }, config)
    result['replied'] = true
  }
  if (params.resolve || params.reopen) {
    const updated = await apiPatch<Thread>(
      base,
      { status: params.resolve ? 'RESOLVED' : 'OPEN' },
      config
    )
    result['resolved'] = updated?.status ? updated.status === 'RESOLVED' : Boolean(params.resolve)
  }
  return result
}

/**
 * Reads a Work Item document's content and `contentHash` — pass the hash back as `baseContentHash` to
 * write_document so a write made over someone else's edit is refused instead of silently winning.
 */
export async function readDocument(
  params: { issueId: string; filename: string },
  config: Config
): Promise<Record<string, unknown>> {
  const row = await findDocument(params.issueId, params.filename, config)
  const doc = await apiGet<DocumentRow>(`${documentsBase(params.issueId, config)}/${row.id}`, config)
  if (doc.content === null || doc.content === undefined) {
    throw new Error(
      `"${params.filename}" has no readable text content (content type ${doc.contentType ?? 'unknown'})`
    )
  }
  return {
    documentId: doc.id,
    filename: doc.filename,
    contentType: doc.contentType,
    contentHash: doc.contentHash ?? null,
    content: doc.content,
  }
}
