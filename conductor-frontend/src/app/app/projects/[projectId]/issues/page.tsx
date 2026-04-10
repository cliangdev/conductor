'use client'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import { useProject } from '@/contexts/ProjectContext'
import { apiGet } from '@/lib/api'
import { Badge } from '@/components/ui/badge'

export const dynamic = 'force-dynamic'

interface Issue {
  id: string
  title: string
  type: string
  status: string
  updatedAt: string
}

interface IssueReviewer {
  userId: string
  name: string
  avatarUrl?: string
  reviewVerdict?: 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'
}

interface IssueWithReviewers extends Issue {
  reviewers?: IssueReviewer[]
}

const STATUS_STYLES: Record<string, string> = {
  DRAFT: 'border-gray-300 bg-gray-100 text-gray-700',
  IN_REVIEW: 'border-blue-300 bg-blue-100 text-blue-700',
  APPROVED: 'border-green-300 bg-green-100 text-green-700',
  CLOSED: 'border-red-300 bg-red-100 text-red-700',
}

const TYPE_OPTIONS = ['All', 'PRD', 'RFC', 'BUG', 'TASK'] as const
const STATUS_OPTIONS = ['All', 'DRAFT', 'IN_REVIEW', 'APPROVED', 'CLOSED'] as const

const VERDICT_ICONS: Record<string, string> = {
  APPROVED: '✅',
  CHANGES_REQUESTED: '🔄',
  COMMENTED: '💬',
}

function verdictIcon(verdict?: string): string {
  if (!verdict) return '⏳'
  return VERDICT_ICONS[verdict] ?? '⏳'
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

export default function IssuesListPage() {
  const params = useParams<{ projectId: string }>()
  const { projectId } = params
  const { accessToken } = useAuth()
  const { projects, setActiveProject } = useProject()

  // Sync the navbar project selector with the current URL
  useEffect(() => {
    const project = projects.find((p) => p.id === projectId)
    if (project) setActiveProject(project)
  }, [projectId, projects])

  const [issues, setIssues] = useState<IssueWithReviewers[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [typeFilter, setTypeFilter] = useState<string>('All')
  const [statusFilter, setStatusFilter] = useState<string>('All')

  useEffect(() => {
    if (!accessToken) return

    async function fetchIssues() {
      try {
        const data = await apiGet<IssueWithReviewers[]>(
          `/api/v1/projects/${projectId}/issues`,
          accessToken!
        )
        setIssues(data)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load issues')
      } finally {
        setLoading(false)
      }
    }

    fetchIssues()
  }, [accessToken, projectId])

  const filteredIssues = issues.filter((issue) => {
    if (typeFilter !== 'All' && issue.type !== typeFilter) return false
    if (statusFilter !== 'All' && issue.status !== statusFilter) return false
    return true
  })

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500">
        Loading issues...
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-64 text-red-500">Error: {error}</div>
    )
  }

  return (
    <div className="p-6">
      <h1 className="text-xl font-semibold text-gray-900 mb-4">Issues</h1>

      <div className="flex gap-3 mb-4">
        <div className="flex items-center gap-2">
          <label htmlFor="type-filter" className="text-sm text-gray-600">Type:</label>
          <select
            id="type-filter"
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
            className="border border-gray-200 rounded-md px-2 py-1 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            {TYPE_OPTIONS.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </div>

        <div className="flex items-center gap-2">
          <label htmlFor="status-filter" className="text-sm text-gray-600">Status:</label>
          <select
            id="status-filter"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="border border-gray-200 rounded-md px-2 py-1 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>{s.replace('_', ' ')}</option>
            ))}
          </select>
        </div>
      </div>

      {filteredIssues.length === 0 ? (
        <div className="flex items-center justify-center h-48 text-gray-400 border border-dashed border-gray-200 rounded-lg">
          No issues yet
        </div>
      ) : (
        <div className="border border-gray-200 rounded-lg overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Title</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Type</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Status</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Reviewers</th>
                <th className="text-left px-4 py-3 text-xs font-semibold text-gray-500 uppercase tracking-wide">Last Updated</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {filteredIssues.map((issue) => (
                <tr key={issue.id} className="hover:bg-gray-50 transition-colors">
                  <td className="px-4 py-3">
                    <Link
                      href={`/app/projects/${projectId}/issues/${issue.id}`}
                      className="text-blue-600 hover:underline font-medium"
                    >
                      {issue.title}
                    </Link>
                  </td>
                  <td className="px-4 py-3">
                    <Badge className="border bg-gray-50 text-gray-600 border-gray-200">
                      {issue.type}
                    </Badge>
                  </td>
                  <td className="px-4 py-3">
                    <Badge className={`border ${STATUS_STYLES[issue.status] ?? STATUS_STYLES.DRAFT}`}>
                      {issue.status.replace('_', ' ')}
                    </Badge>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1">
                      {issue.reviewers && issue.reviewers.length > 0 ? (
                        issue.reviewers.map((r) => (
                          <div
                            key={r.userId}
                            className="flex items-center gap-0.5"
                            title={r.name}
                          >
                            {r.avatarUrl ? (
                              // eslint-disable-next-line @next/next/no-img-element
                              <img
                                src={r.avatarUrl}
                                alt={r.name}
                                className="w-5 h-5 rounded-full border border-gray-200"
                              />
                            ) : (
                              <div className="w-5 h-5 rounded-full bg-gray-300 flex items-center justify-center text-xs font-medium text-gray-600">
                                {r.name.charAt(0).toUpperCase()}
                              </div>
                            )}
                            <span className="text-xs">{verdictIcon(r.reviewVerdict)}</span>
                          </div>
                        ))
                      ) : (
                        <span className="text-gray-400 text-xs">—</span>
                      )}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-gray-500">{formatDate(issue.updatedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
