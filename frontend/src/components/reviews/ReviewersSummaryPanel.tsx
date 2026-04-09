interface Reviewer {
  userId: string
  name: string
  email: string
  avatarUrl?: string
  reviewVerdict?: 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'
}

interface ReviewersSummaryPanelProps {
  reviewers: Reviewer[]
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

export function ReviewersSummaryPanel({ reviewers }: ReviewersSummaryPanelProps) {
  if (reviewers.length === 0) {
    return (
      <div className="flex items-center gap-2 mt-3">
        <span className="text-xs text-gray-500">No reviewers assigned yet</span>
      </div>
    )
  }

  return (
    <div className="flex items-center gap-2 mt-3">
      <span className="text-xs text-gray-500">Reviewers:</span>
      {reviewers.map((r) => (
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
  )
}
