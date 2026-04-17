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

interface ReviewersSummaryPanelProps {
  reviewers: Reviewer[]
  canManage?: boolean
  onUnassign?: (userId: string) => void
  reviews?: Review[]
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

export function ReviewersSummaryPanel({
  reviewers,
  canManage = false,
  onUnassign,
  reviews = [],
}: ReviewersSummaryPanelProps) {
  if (reviewers.length === 0) {
    return (
      <div className="flex items-center gap-2 mt-3">
        <span className="text-xs text-muted-foreground">No reviewers assigned yet</span>
      </div>
    )
  }

  return (
    <div className="mt-3">
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-xs text-muted-foreground">Reviewers:</span>
        {reviewers.map((r) => {
          const review = reviews.find((rv) => rv.reviewerId === r.userId)
          return (
            <div
              key={r.userId}
              className="flex items-center gap-1"
              title={`${r.name} (${r.email})`}
            >
              {r.avatarUrl ? (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={r.avatarUrl}
                  alt={r.name}
                  className="w-6 h-6 rounded-full border border-border"
                />
              ) : (
                <div className="w-6 h-6 rounded-full bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground">
                  {r.name.charAt(0).toUpperCase()}
                </div>
              )}
              <span className="text-sm">{verdictIcon(r.reviewVerdict)}</span>
              {review?.body && (
                <span
                  className="text-xs text-muted-foreground truncate max-w-[80px]"
                  title={review.body}
                >
                  {review.body.length > 80 ? review.body.slice(0, 80) + '…' : review.body}
                </span>
              )}
              {canManage && onUnassign && (
                <button
                  onClick={() => onUnassign(r.userId)}
                  className="px-1 text-xs text-destructive hover:text-destructive leading-none"
                  title={`Unassign ${r.name}`}
                  aria-label={`Unassign ${r.name}`}
                >
                  ×
                </button>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
