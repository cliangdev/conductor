'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { apiPost, apiErrorMessage } from '@/lib/api'
import { VerdictIcon } from './verdict'
import type { ReviewOutcome } from '@/types/workItem'

type Verdict = 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'

interface ReviewSubmissionFormProps {
  projectId: string
  issueId: string
  token: string
  isAssignedReviewer: boolean
  existingVerdict?: Verdict
  existingBody?: string
  /**
   * The review-gated transition's allowed outcomes (from the Workflow). When provided, only those
   * verdicts are offered; otherwise all three are shown.
   */
  reviewOutcomes?: ReviewOutcome[]
  onReviewSubmitted: () => void
}

const VERDICT_OPTIONS: { value: Verdict; label: string; outcome: ReviewOutcome }[] = [
  { value: 'APPROVED', label: 'Approve', outcome: 'approve' },
  { value: 'CHANGES_REQUESTED', label: 'Request Changes', outcome: 'request_changes' },
  { value: 'COMMENTED', label: 'Comment', outcome: 'comment' },
]

export function ReviewSubmissionForm({
  projectId,
  issueId,
  token,
  isAssignedReviewer,
  existingVerdict,
  existingBody,
  reviewOutcomes,
  onReviewSubmitted,
}: ReviewSubmissionFormProps) {
  // Narrow to the Workflow's allowed outcomes when known; fall back to all three.
  const verdictOptions =
    reviewOutcomes && reviewOutcomes.length > 0
      ? VERDICT_OPTIONS.filter((o) => reviewOutcomes.includes(o.outcome))
      : VERDICT_OPTIONS
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
        `/api/v2/projects/${projectId}/work-items/${issueId}/reviews`,
        { verdict: selectedVerdict, body: body || undefined },
        token
      )
      onReviewSubmitted()
    } catch (err) {
      setError(apiErrorMessage(err, 'Failed to submit review. Please try again.'))
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
        {verdictOptions.map((option) => (
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
            <VerdictIcon verdict={option.value} />
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
