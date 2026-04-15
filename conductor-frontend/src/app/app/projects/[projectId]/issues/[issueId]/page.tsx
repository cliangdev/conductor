'use client'

import { useEffect, useState, useCallback, useRef } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, apiPost, apiDelete } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { ExternalLink } from 'lucide-react'
import { CommentableDocument } from '@/components/comments/CommentableDocument'
import { ReviewSubmissionForm } from '@/components/reviews/ReviewSubmissionForm'
import { ReviewersSummaryPanel } from '@/components/reviews/ReviewersSummaryPanel'
import { StatusDropdown } from '@/components/issues/StatusDropdown'
import { TaskProgressPanel } from '@/components/issues/TaskProgressPanel'
import type { Comment } from '@/components/comments/types'
import type { MemberRole } from '@/types'

export const dynamic = 'force-dynamic'

interface Issue {
  id: string
  title: string
  type: string
  status: string
  description?: string
  displayId?: string
  githubPrUrl?: string | null
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
  name?: string
  email?: string
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

type MobileTab = 'documents' | 'content'

export default function IssueDetailPage() {
  const params = useParams<{ projectId: string; issueId: string }>()
  const { projectId, issueId } = params
  const { accessToken, user } = useAuth()

  const [issue, setIssue] = useState<Issue | null>(null)
  const [documents, setDocuments] = useState<Document[]>([])
  const [reviewers, setReviewers] = useState<Reviewer[]>([])
  const [reviews, setReviews] = useState<Review[]>([])
  const [userRole, setUserRole] = useState<MemberRole>('REVIEWER')
  const [allMembers, setAllMembers] = useState<Member[]>([])
  const [selectedDocId, setSelectedDocId] = useState<string | null>(null)
  const [comments, setComments] = useState<Comment[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<MobileTab>('documents')
  const [assignDropdownOpen, setAssignDropdownOpen] = useState(false)
  const [assigning, setAssigning] = useState(false)
  const assignDropdownRef = useRef<HTMLDivElement>(null)

  const fetchDocuments = useCallback(async () => {
    if (!accessToken) return
    const docs = await apiGet<Document[]>(
      `/api/v1/projects/${projectId}/issues/${issueId}/documents`,
      accessToken
    )
    setDocuments(docs.filter((d) => d.filename !== 'tasks.json'))
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
      // Non-fatal
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
          setAllMembers(members)
          const currentMember = members.find((m) => m.userId === user?.id)
          if (currentMember) setUserRole(currentMember.role)
        } catch {
          // Default to REVIEWER
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
      setActiveTab('content')
    } else if (doc.storageUrl) {
      window.open(doc.storageUrl, '_blank', 'noopener,noreferrer')
    }
  }

  const isAssignedReviewer = reviewers.some((r) => r.userId === user?.id)
  const currentUserReview = reviews.find((r) => r.reviewerId === user?.id)
  const canManage = userRole === 'CREATOR' || userRole === 'ADMIN'

  const assignedIds = new Set(reviewers.map((r) => r.userId))
  const assignableMembers = allMembers.filter(
    (m) => m.role === 'REVIEWER' && !assignedIds.has(m.userId)
  )

  async function handleUnassign(userId: string) {
    if (!accessToken) return
    try {
      await apiDelete(
        `/api/v1/projects/${projectId}/issues/${issueId}/reviewers/${userId}`,
        accessToken
      )
      await fetchReviewers()
    } catch {
      // Non-fatal
    }
  }

  async function handleAssign(userId: string) {
    if (!accessToken || assigning) return
    setAssigning(true)
    try {
      await apiPost(
        `/api/v1/projects/${projectId}/issues/${issueId}/reviewers`,
        { userId },
        accessToken
      )
      await fetchReviewers()
      setAssignDropdownOpen(false)
    } catch {
      // Non-fatal
    } finally {
      setAssigning(false)
    }
  }

  useEffect(() => {
    if (!assignDropdownOpen) return
    function handleClickOutside(e: MouseEvent) {
      if (assignDropdownRef.current && !assignDropdownRef.current.contains(e.target as Node)) {
        setAssignDropdownOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [assignDropdownOpen])

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64 text-muted-foreground">
        Loading issue...
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-64 text-destructive">Error: {error}</div>
    )
  }

  if (!issue) return null

  const sidebar = (
    <aside className="w-full md:w-72 border-r border-border bg-sidebar-bg flex flex-col shrink-0 overflow-y-auto">
      <div className="px-4 py-3 border-b border-border">
        <span className="text-xs font-semibold text-foreground-subtle uppercase tracking-wide">
          Documents
        </span>
      </div>
      {documents.length === 0 ? (
        <div className="px-4 py-6 text-sm text-muted-foreground text-center">
          No documents attached yet
        </div>
      ) : (
        <ul className="py-2">
          {documents.map((doc) => (
            <li key={doc.id}>
              <button
                onClick={() => handleDocClick(doc)}
                className={`w-full text-left px-4 py-2 text-sm truncate transition-colors ${
                  selectedDocId === doc.id
                    ? 'bg-sidebar-active text-sidebar-active-text font-medium'
                    : 'text-foreground hover:bg-sidebar-hover'
                }`}
                title={doc.filename}
              >
                {doc.filename}
                {!isMarkdown(doc) && (
                  <span className="ml-1 text-xs text-muted-foreground">(binary)</span>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}

      <TaskProgressPanel issueId={issueId} projectId={projectId} />

      {(isAssignedReviewer || userRole === 'REVIEWER') && (
        <div className="mt-auto border-t border-border p-4">
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
  )

  const mainContent = (
    <main className="flex-1 overflow-y-auto p-4 md:p-6">
      {documents.length === 0 ? (
        <div className="flex items-center justify-center h-full text-muted-foreground">
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
          onDocumentNavigate={(filename) => {
            const target = documents.find((d) => d.filename === filename)
            if (target) {
              setSelectedDocId(target.id)
              setActiveTab('content')
            }
          }}
        />
      ) : selectedDoc && isMarkdown(selectedDoc) && !selectedDoc.content ? (
        <div className="text-muted-foreground text-sm">Document content is empty.</div>
      ) : (
        <div className="text-muted-foreground text-sm">
          Select a document from the sidebar to view its contents.
        </div>
      )}
    </main>
  )

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="border-b border-border bg-background px-4 sm:px-6 py-4 shrink-0">
        <div className="flex items-start gap-3 flex-wrap">
          {issue.displayId && (
            <span className="font-mono text-xs text-muted-foreground self-center shrink-0">
              {issue.displayId}
            </span>
          )}
          <h1 className="text-lg sm:text-xl font-semibold text-foreground flex-1 min-w-0">
            {issue.title}
          </h1>
          <div className="flex items-center gap-2 shrink-0">
            <Badge variant="outline">{issue.type}</Badge>
            <StatusDropdown
              projectId={projectId}
              issueId={issueId}
              currentStatus={issue.status}
              userRole={userRole}
              token={accessToken!}
              onStatusChanged={(s) => setIssue((prev) => prev ? { ...prev, status: s } : prev)}
            />
            {issue.githubPrUrl && (
              <a
                href={issue.githubPrUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground transition-colors"
              >
                <ExternalLink className="h-3.5 w-3.5" />
                View PR
              </a>
            )}
          </div>
        </div>
        <div className="flex items-start gap-3 flex-wrap">
          <div className="flex-1 min-w-0">
            <ReviewersSummaryPanel
              reviewers={reviewers}
              canManage={canManage}
              onUnassign={handleUnassign}
              reviews={reviews}
            />
          </div>
          {canManage && (
            <div className="relative shrink-0 mt-3" ref={assignDropdownRef}>
              <button
                onClick={() => setAssignDropdownOpen((prev) => !prev)}
                className="px-2 py-1 text-xs border border-border rounded bg-background text-foreground hover:bg-muted transition-colors"
              >
                + Assign Reviewer
              </button>
              {assignDropdownOpen && (
                <div className="absolute right-0 top-full mt-1 z-50 w-56 bg-background border border-border rounded shadow-md">
                  {assignableMembers.length === 0 ? (
                    <div className="px-3 py-2 text-xs text-muted-foreground">
                      No reviewers available to assign
                    </div>
                  ) : (
                    <ul className="py-1">
                      {assignableMembers.map((m) => (
                        <li key={m.userId}>
                          <button
                            onClick={() => handleAssign(m.userId)}
                            disabled={assigning}
                            className="w-full flex items-center gap-2 px-3 py-2 text-sm text-foreground hover:bg-muted transition-colors disabled:opacity-50"
                          >
                            <div className="w-6 h-6 rounded-full bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground shrink-0">
                              {(m.name ?? m.email ?? '?').charAt(0).toUpperCase()}
                            </div>
                            <div className="flex flex-col items-start min-w-0">
                              <span className="truncate max-w-full">{m.name ?? m.email}</span>
                              {m.name && m.email && (
                                <span className="text-xs text-muted-foreground truncate max-w-full">
                                  {m.email}
                                </span>
                              )}
                            </div>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Mobile tab bar */}
      <div className="md:hidden flex border-b border-border bg-background shrink-0">
        {(['documents', 'content'] as MobileTab[]).map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`flex-1 py-2.5 text-sm font-medium transition-colors capitalize ${
              activeTab === tab
                ? 'text-primary border-b-2 border-primary'
                : 'text-muted-foreground hover:text-foreground'
            }`}
          >
            {tab === 'documents' ? 'Documents' : 'Content'}
          </button>
        ))}
      </div>

      {/* Body */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar: visible on desktop always; on mobile only when documents tab active */}
        <div className={`${activeTab === 'documents' ? 'flex' : 'hidden'} md:flex w-full md:w-auto`}>
          {sidebar}
        </div>

        {/* Main: visible on desktop always; on mobile only when content tab active */}
        <div className={`${activeTab === 'content' ? 'flex' : 'hidden'} md:flex flex-1 overflow-hidden`}>
          {mainContent}
        </div>
      </div>
    </div>
  )
}
