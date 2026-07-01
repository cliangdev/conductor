import { Config } from '../config.js'
import { apiGet } from '../api.js'

interface CommentReply {
  id: string
  content: string
  authorName: string
  createdAt: string
}

interface CommentResponse {
  id: string
  issueId: string
  documentId: string
  documentName: string
  lineNumber: number | null
  quotedText: string | null
  lineStale: boolean
  content: string
  authorName: string
  createdAt: string
  resolvedAt: string | null
  replies: CommentReply[]
}

async function listCommentsAt(
  path: string,
  config: Config
): Promise<unknown[]> {
  try {
    const result = await apiGet<CommentResponse[]>(path, config)
    return result ?? []
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    if (message.includes('API error 404')) {
      return []
    }
    throw err
  }
}

function commentsQuery(resolved?: boolean): string {
  const query = new URLSearchParams()
  if (resolved !== undefined) {
    query.set('resolved', String(resolved))
  }
  const qs = query.toString()
  return qs ? `?${qs}` : ''
}

// Canonical `list_work_item_comments` handler — v2 work-items sub-resource.
export async function listWorkItemComments(
  params: { issueId: string; resolved?: boolean },
  config: Config
): Promise<unknown[]> {
  const path = `/api/v2/projects/${config.projectId}/work-items/${params.issueId}/comments${commentsQuery(params.resolved)}`
  return listCommentsAt(path, config)
}

// Deprecated `list_issue_comments` handler — legacy v1 issues sub-resource (kept as a shim).
export async function listIssueComments(
  params: { issueId: string; resolved?: boolean },
  config: Config
): Promise<unknown[]> {
  const path = `/api/v1/projects/${config.projectId}/issues/${params.issueId}/comments${commentsQuery(params.resolved)}`
  return listCommentsAt(path, config)
}
