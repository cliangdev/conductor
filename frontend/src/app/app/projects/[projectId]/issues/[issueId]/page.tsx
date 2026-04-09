'use client'

import { useEffect, useState, useCallback } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'

export const dynamic = 'force-dynamic'

interface Issue {
  id: string
  title: string
  type: string
  status: string
  description?: string
}

interface Document {
  id: string
  filename: string
  contentType: string
  content?: string
  storageUrl?: string
  storageUrlExpiresAt?: string
}

interface Reviewer {
  userId: string
  name: string
  email: string
  avatarUrl?: string
  reviewVerdict?: 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'
}

const STATUS_STYLES: Record<string, string> = {
  DRAFT: 'border-gray-300 bg-gray-100 text-gray-700',
  IN_REVIEW: 'border-blue-300 bg-blue-100 text-blue-700',
  APPROVED: 'border-green-300 bg-green-100 text-green-700',
  CLOSED: 'border-red-300 bg-red-100 text-red-700',
}

const VERDICT_ICONS: Record<string, string> = {
  APPROVED: '✅',
  CHANGES_REQUESTED: '🔄',
  COMMENTED: '💬',
}

function verdictIcon(verdict?: string): string {
  if (!verdict) return '⏳'
  return VERDICT_ICONS[verdict] ?? '⏳'
}

function isMarkdown(doc: Document): boolean {
  return (
    doc.contentType === 'text/markdown' ||
    doc.contentType === 'text/plain' ||
    doc.filename.endsWith('.md')
  )
}

function isExpiringSoon(doc: Document): boolean {
  if (!doc.storageUrlExpiresAt) return false
  const expiresAt = new Date(doc.storageUrlExpiresAt).getTime()
  return expiresAt - Date.now() < 60_000
}

export default function IssueDetailPage() {
  const params = useParams<{ projectId: string; issueId: string }>()
  const { projectId, issueId } = params
  const { accessToken } = useAuth()

  const [issue, setIssue] = useState<Issue | null>(null)
  const [documents, setDocuments] = useState<Document[]>([])
  const [reviewers, setReviewers] = useState<Reviewer[]>([])
  const [selectedDocId, setSelectedDocId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchDocuments = useCallback(async () => {
    if (!accessToken) return
    const docs = await apiGet<Document[]>(
      `/api/v1/projects/${projectId}/issues/${issueId}/documents`,
      accessToken
    )
    setDocuments(docs)
    setSelectedDocId((prev) => {
      if (prev) return prev
      const firstMd = docs.find(isMarkdown)
      return firstMd?.id ?? docs[0]?.id ?? null
    })
  }, [accessToken, projectId, issueId])

  useEffect(() => {
    if (!accessToken) return

    async function fetchAll() {
      try {
        const [issueData, reviewerData] = await Promise.all([
          apiGet<Issue>(`/api/v1/projects/${projectId}/issues/${issueId}`, accessToken!),
          apiGet<Reviewer[]>(
            `/api/v1/projects/${projectId}/issues/${issueId}/reviewers`,
            accessToken!
          ),
        ])
        setIssue(issueData)
        setReviewers(reviewerData)
        await fetchDocuments()
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load issue')
      } finally {
        setLoading(false)
      }
    }

    fetchAll()
  }, [accessToken, projectId, issueId, fetchDocuments])

  // Re-fetch documents if any signed URL is expiring within 60 seconds
  useEffect(() => {
    if (documents.length === 0) return
    const expiring = documents.filter(isExpiringSoon)
    if (expiring.length === 0) return
    fetchDocuments().catch(() => {})
  }, [documents, fetchDocuments])

  const selectedDoc = documents.find((d) => d.id === selectedDocId) ?? null

  function handleDocClick(doc: Document) {
    if (isMarkdown(doc)) {
      setSelectedDocId(doc.id)
    } else if (doc.storageUrl) {
      window.open(doc.storageUrl, '_blank', 'noopener,noreferrer')
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500">Loading issue...</div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-64 text-red-500">Error: {error}</div>
    )
  }

  if (!issue) return null

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="border-b bg-white px-6 py-4">
        <div className="flex items-start gap-3 flex-wrap">
          <h1 className="text-xl font-semibold text-gray-900 flex-1">{issue.title}</h1>
          <div className="flex items-center gap-2">
            <Badge className="border bg-gray-50 text-gray-600 border-gray-200">{issue.type}</Badge>
            <Badge className={`border ${STATUS_STYLES[issue.status] ?? STATUS_STYLES.DRAFT}`}>
              {issue.status.replace('_', ' ')}
            </Badge>
          </div>
        </div>

        {reviewers.length > 0 && (
          <div className="flex items-center gap-2 mt-3">
            <span className="text-xs text-gray-500">Reviewers:</span>
            {reviewers.map((r) => (
              <div key={r.userId} className="flex items-center gap-1" title={`${r.name} (${r.email})`}>
                {r.avatarUrl ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={r.avatarUrl}
                    alt={r.name}
                    className="w-6 h-6 rounded-full border border-gray-200"
                  />
                ) : (
                  <div className="w-6 h-6 rounded-full bg-gray-300 flex items-center justify-center text-xs font-medium text-gray-600">
                    {r.name.charAt(0).toUpperCase()}
                  </div>
                )}
                <span className="text-sm">{verdictIcon(r.reviewVerdict)}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Body: sidebar + main */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar: document list */}
        <aside className="w-56 border-r bg-gray-50 flex flex-col shrink-0 overflow-y-auto">
          <div className="px-4 py-3 border-b">
            <span className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              Documents
            </span>
          </div>
          {documents.length === 0 ? (
            <div className="px-4 py-6 text-sm text-gray-400 text-center">
              No documents attached yet
            </div>
          ) : (
            <ul className="py-2">
              {documents.map((doc) => (
                <li key={doc.id}>
                  <button
                    onClick={() => handleDocClick(doc)}
                    className={`w-full text-left px-4 py-2 text-sm truncate hover:bg-gray-100 transition-colors ${
                      selectedDocId === doc.id ? 'bg-blue-50 text-blue-700 font-medium' : 'text-gray-700'
                    }`}
                    title={doc.filename}
                  >
                    {doc.filename}
                    {!isMarkdown(doc) && (
                      <span className="ml-1 text-xs text-gray-400">(binary)</span>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </aside>

        {/* Main content */}
        <main className="flex-1 overflow-y-auto p-6">
          {documents.length === 0 ? (
            <div className="flex items-center justify-center h-full text-gray-400">
              No documents attached yet
            </div>
          ) : selectedDoc && isMarkdown(selectedDoc) && selectedDoc.content ? (
            <MarkdownRenderer content={selectedDoc.content} />
          ) : selectedDoc && isMarkdown(selectedDoc) && !selectedDoc.content ? (
            <div className="text-gray-400 text-sm">Document content is empty.</div>
          ) : (
            <div className="text-gray-400 text-sm">
              Select a document from the sidebar to view its contents.
            </div>
          )}
        </main>
      </div>
    </div>
  )
}
