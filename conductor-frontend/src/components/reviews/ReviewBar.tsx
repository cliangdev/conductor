'use client'

// The GitHub-style batch review bar (COND-22): sticky at the bottom of the Work Item detail view
// while a reviewer is "in review mode". Comments drafted via CommentableDocument during this window
// are held client-side (see WorkItemDetailView's pending-comment state) — this bar is where the
// reviewer finally posts them, together with one verdict and an optional summary.

import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import type { ReviewOutcome } from '@/types/workItem'
import type { Verdict } from '@/components/reviews/verdict'

const VERDICT_OPTIONS: { value: Verdict; label: string; outcome: ReviewOutcome }[] = [
  { value: 'COMMENTED', label: 'Comment', outcome: 'comment' },
  { value: 'CHANGES_REQUESTED', label: 'Request changes', outcome: 'request_changes' },
  { value: 'APPROVED', label: 'Approve', outcome: 'approve' },
]

export function ReviewBar({
  pendingCount,
  reviewOutcomes,
  submitting,
  onSubmit,
  onCancel,
}: {
  pendingCount: number
  /** The review-gated transition's allowed outcomes (from the Workflow). Only those verdicts are
   * offered; when absent or empty (no review gate on the current status), the bar renders nothing —
   * it never falls back to offering all three. */
  reviewOutcomes?: ReviewOutcome[]
  submitting: boolean
  onSubmit: (verdict: Verdict, summary: string) => void
  onCancel: () => void
}) {
  const verdictOptions = (reviewOutcomes ?? []).length
    ? VERDICT_OPTIONS.filter((o) => reviewOutcomes!.includes(o.outcome))
    : []
  const [summaryOpen, setSummaryOpen] = useState(false)
  const [summary, setSummary] = useState('')
  // Tracks which verdict button was clicked so only that one switches to "Submitting…" — the other
  // two stay labeled (just disabled) instead of all three going ambiguously blank/busy at once.
  const [clickedVerdict, setClickedVerdict] = useState<Verdict | null>(null)

  const barRef = useRef<HTMLDivElement>(null)

  // The bar only mounts once review mode starts (see WorkItemDetailView) — move focus into it right
  // away so a keyboard user isn't left wherever they were when the sticky bar appeared underneath them.
  useEffect(() => {
    barRef.current?.focus()
  }, [])

  function handleSubmit(verdict: Verdict) {
    setClickedVerdict(verdict)
    onSubmit(verdict, summary)
  }

  // No review-gated transition (or one with no allowed outcomes) means there's nothing valid to
  // submit — render nothing rather than falling back to offering all three verdicts, which could
  // let a reviewer "approve" a status that was never gated on review in the first place.
  if (verdictOptions.length === 0) return null

  return (
    <div
      ref={barRef}
      data-testid="review-bar"
      role="region"
      aria-label="Review in progress"
      tabIndex={-1}
      className="sticky bottom-0 z-30 border-t border-border bg-surface focus:outline-none"
      onKeyDown={(e) => {
        if (e.key === 'Escape') {
          e.stopPropagation()
          onCancel()
        }
      }}
    >
      {summaryOpen && (
        <div className="px-4 pt-3">
          <Textarea
            value={summary}
            onChange={(e) => setSummary(e.target.value)}
            placeholder="Add a summary (optional)"
            aria-label="Review summary"
            rows={2}
            disabled={submitting}
            autoFocus
          />
        </div>
      )}
      <div className="flex items-center gap-3 px-4 py-3">
        <span className="text-sm text-foreground flex-1 min-w-0">
          Reviewing — {pendingCount} pending comment{pendingCount !== 1 ? 's' : ''}
        </span>
        {!summaryOpen && (
          <Button variant="link" size="sm" onClick={() => setSummaryOpen(true)} disabled={submitting}>
            Add summary
          </Button>
        )}
        <Button variant="ghost" size="sm" onClick={onCancel} disabled={submitting}>
          Cancel
        </Button>
        {verdictOptions.map((option) => (
          <Button
            key={option.value}
            size="sm"
            variant={option.value === 'APPROVED' ? 'success' : option.value === 'CHANGES_REQUESTED' ? 'outline' : 'ghost'}
            disabled={submitting}
            onClick={() => handleSubmit(option.value)}
          >
            {submitting && clickedVerdict === option.value ? 'Submitting…' : option.label}
          </Button>
        ))}
      </div>
    </div>
  )
}
