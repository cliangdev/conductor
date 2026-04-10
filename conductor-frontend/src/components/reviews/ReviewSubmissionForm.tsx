'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { apiPost } from '@/lib/api'

type Verdict = 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'

interface ReviewSubmissionFormProps {
  projectId: string
  issueId: string
  token: string
  isAssignedReviewer: boolean
  existingVerdict?: Verdict
  existingBody?: string
  onReviewSubmitted: () => void
}

const VERDICT_OPTIONS: { value: Verdict; label: string; icon: string }[] = [
  { value: 'APPROVED', label: 'Approve', icon: '✅' },
  { value: 'CHANGES_REQUESTED', label: 'Request Changes', icon: '🔄' },
  { value: 'COMMENTED', label: 'Comment', icon: '💬' },
]

export function ReviewSubmissionForm({
  projectId,
  issueId,
  token,
  isAssignedReviewer,
  existingVerdict,
  existingBody,
  onReviewSubmitted,
}: ReviewSubmissionFormProps) {
  const [selectedVerdict, setSelectedVerdict] = useState<Verdict | null>(existingVerdict ?? null)
  const [body, setBody] = useState(existingBody ?? '')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit() {
    if (!selectedVerdict) return
    setSubmitting(true)
    setError(null)
    try {
      await apiPost(
        `/api/v1/projects/${projectId}/issues/${issueId}/reviews`,
        { verdict: selectedVerdict, body: body || undefined },
        token
      )
      onReviewSubmitted()
    } catch (err) {
      const apiErr = err as Error & { status?: number }
      if (apiErr.status === 403) {
        setError('You do not have permission to submit a review.')
      } else {
        setError('Failed to submit review. Please try again.')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="border rounded-lg p-4 bg-white">
      <h3 className="text-sm font-semibold text-gray-900 mb-3">Submit Review</h3>

      {!isAssignedReviewer && (
        <p className="text-xs text-gray-500 italic mb-3">You are not an assigned reviewer</p>
      )}

      <div className="flex gap-2 mb-3">
        {VERDICT_OPTIONS.map((option) => (
          <button
            key={option.value}
            onClick={() => isAssignedReviewer && setSelectedVerdict(option.value)}
            disabled={!isAssignedReviewer}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md border text-sm transition-colors ${
              selectedVerdict === option.value
                ? 'border-blue-500 bg-blue-50 text-blue-700 font-medium'
                : 'border-gray-200 bg-gray-50 text-gray-600 hover:bg-gray-100'
            } disabled:opacity-50 disabled:cursor-not-allowed`}
          >
            <span>{option.icon}</span>
            <span>{option.label}</span>
          </button>
        ))}
      </div>

      <textarea
        value={body}
        onChange={(e) => setBody(e.target.value)}
        disabled={!isAssignedReviewer}
        placeholder="Add a comment (optional)"
        rows={3}
        className="w-full border border-gray-200 rounded-md px-3 py-2 text-sm text-gray-700 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none disabled:opacity-50 disabled:bg-gray-50"
      />

      {error && <p className="text-xs text-red-600 mt-2">{error}</p>}

      <div className="mt-3 flex justify-end">
        <Button
          size="sm"
          onClick={handleSubmit}
          disabled={!isAssignedReviewer || !selectedVerdict || submitting}
        >
          {submitting ? 'Submitting...' : existingVerdict ? 'Update Review' : 'Submit Review'}
        </Button>
      </div>
    </div>
  )
}
