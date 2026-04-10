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
    <div className="border border-border rounded-lg p-4 bg-card">
      <h3 className="text-sm font-semibold text-foreground mb-3">Submit Review</h3>

      {!isAssignedReviewer && (
        <p className="text-xs text-muted-foreground italic mb-3">You are not an assigned reviewer</p>
      )}

      <div className="flex flex-wrap gap-2 mb-3">
        {VERDICT_OPTIONS.map((option) => (
          <button
            key={option.value}
            onClick={() => isAssignedReviewer && setSelectedVerdict(option.value)}
            disabled={!isAssignedReviewer}
            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md border text-sm transition-colors ${
              selectedVerdict === option.value
                ? 'border-primary bg-primary/10 text-primary font-medium'
                : 'border-border bg-muted text-muted-foreground hover:bg-muted/80'
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
        className="w-full border border-input bg-background text-foreground rounded-md px-3 py-2 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring resize-none disabled:opacity-50 disabled:bg-muted"
      />

      {error && <p className="text-xs text-destructive mt-2">{error}</p>}

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
