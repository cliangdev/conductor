'use client'

import { useEffect, useState, useCallback } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { CommentableDocument } from '@/components/comments/CommentableDocument'
import { ReviewSubmissionForm } from '@/components/reviews/ReviewSubmissionForm'
import { ReviewersSummaryPanel } from '@/components/reviews/ReviewersSummaryPanel'
import { StatusDropdown } from '@/components/issues/StatusDropdown'
import type { Comment } from '@/components/comments/types'
import type { MemberRole } from '@/types'

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

interface Review {
  reviewerId: string
  name: string
  avatarUrl?: string
  verdict: 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'
  body?: string
  submittedAt: string
}

interface Member {
  userId: string
  role: MemberRole
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
  const { accessToken, user } = useAuth()

  const [issue, setIssue] = useState<Issue | null>(null)
  const [documents, setDocuments] = useState<Document[]>([])
  const [reviewers, setReviewers] = useState<Reviewer[]>([])
  const [reviews, setReviews] = useState<Review[]>([])
  const [userRole, setUserRole] = useState<MemberRole>('REVIEWER')
  const [selectedDocId, setSelectedDocId] = useState<string | null>(null)
  const [comments, setComments] = useState<Comment[]>([])
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

  const fetchComments = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<Comment[]>(
        `/api/v1/projects/${projectId}/issues/${issueId}/comments`,
        accessToken
      )
      setComments(data)
    } catch {
      // Non-fatal: comments failing to load shouldn't break the page
    }
  }, [accessToken, projectId, issueId])

  const fetchReviewers = useCallback(async () => {
    if (!accessToken) return
    const data = await apiGet<Reviewer[]>(
      `/api/v1/projects/${projectId}/issues/${issueId}/reviewers`,
      accessToken
    )
    setReviewers(data)
  }, [accessToken, projectId, issueId])

  const fetchReviews = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<Review[]>(
        `/api/v1/projects/${projectId}/issues/${issueId}/reviews`,
        accessToken
      )
      setReviews(data)
    } catch {
      // Non-fatal
    }
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

        await Promise.all([fetchDocuments(), fetchComments(), fetchReviews()])

        try {
          const members = await apiGet<Member[]>(
            `/api/v1/projects/${projectId}/members`,
            accessToken!
          )
          const currentMember = members.find((m) => m.userId === user?.id)
          if (currentMember) {
            setUserRole(currentMember.role)
          }
        } catch {
          // Default to REVIEWER if members fetch fails
        }
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load issue')
      } finally {
        setLoading(false)
      }
    }

    fetchAll()
  }, [accessToken, projectId, issueId, fetchDocuments, fetchComments, fetchReviews, user?.id])

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

  const isAssignedReviewer = reviewers.some((r) => r.userId === user?.id)
  const currentUserReview = reviews.find((r) => r.reviewerId === user?.id)

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
            <StatusDropdown
              projectId={projectId}
              issueId={issueId}
              currentStatus={issue.status}
              userRole={userRole}
              token={accessToken!}
              onStatusChanged={(s) => setIssue((prev) => prev ? { ...prev, status: s } : prev)}
            />
          </div>
        </div>

        <ReviewersSummaryPanel reviewers={reviewers} />
      </div>

      {/* Body: sidebar + main */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar: document list + review form */}
        <aside className="w-72 border-r bg-gray-50 flex flex-col shrink-0 overflow-y-auto">
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

          {(isAssignedReviewer || userRole === 'REVIEWER') && (
            <div className="mt-auto border-t p-4">
              <ReviewSubmissionForm
                projectId={projectId}
                issueId={issueId}
                token={accessToken!}
                isAssignedReviewer={isAssignedReviewer}
                existingVerdict={currentUserReview?.verdict}
                existingBody={currentUserReview?.body}
                onReviewSubmitted={async () => {
                  await Promise.all([fetchReviewers(), fetchReviews()])
                }}
              />
            </div>
          )}
        </aside>

        {/* Main content */}
        <main className="flex-1 overflow-y-auto p-6">
          {documents.length === 0 ? (
            <div className="flex items-center justify-center h-full text-gray-400">
              No documents attached yet
            </div>
          ) : selectedDoc && isMarkdown(selectedDoc) && selectedDoc.content ? (
            <CommentableDocument
              content={selectedDoc.content}
              documentId={selectedDoc.id}
              issueId={issueId}
              projectId={projectId}
              comments={comments.filter((c) => c.documentId === selectedDoc.id)}
              onCommentAdded={fetchComments}
              token={accessToken!}
              currentUserId={user?.id ?? ''}
            />
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
