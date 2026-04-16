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

export async function listIssueComments(
  params: { issueId: string; resolved?: boolean },
  config: Config
): Promise<unknown[]> {
  const query = new URLSearchParams()
  if (params.resolved !== undefined) {
    query.set('resolved', String(params.resolved))
  }

  const qs = query.toString()
  const path = `/api/v1/projects/${config.projectId}/issues/${params.issueId}/comments${qs ? `?${qs}` : ''}`

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
