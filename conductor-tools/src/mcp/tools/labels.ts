/**
 * Human words for the state and status codes Post tools hand back, so a person reading a tool's
 * output sees "Waiting" next to PENDING rather than the bare wire code — the design system's
 * "translate at the UI boundary" rule, applied to the MCP surface too.
 */

/**
 * Human words for a publish target's wire `state`. Copied verbatim from `STATE_LABELS` in
 * conductor-frontend/src/components/marketing/PublishOutcomePanel.tsx so the CLI and the web app
 * agree on what a person calls each state — do not diverge without updating both.
 */
export const STATE_LABELS: Record<string, string> = {
  PENDING: 'Waiting',
  HANDED_OFF: 'Handed off',
  PUBLISHING: 'Publishing',
  AWAITING_MANUAL: 'Post it now',
  PUBLISHED: 'Published',
  FAILED: 'Failed',
  REVOKED: 'Taken back',
}

/** "IN_REVIEW" -> "In Review". The fallback for any code with no known label. */
export function titleCase(id: string): string {
  return id
    .toLowerCase()
    .split('_')
    .filter(Boolean)
    .map((word) => word[0]!.toUpperCase() + word.slice(1))
    .join(' ')
}

/** A publish target's `state`, in words: STATE_LABELS when known, else Title Case. */
export function stateLabel(state: string | undefined | null): string | undefined {
  if (!state) return undefined
  return STATE_LABELS[state] ?? titleCase(state)
}

/**
 * A Work Item's `status`, in words. Prefers the label the Workflow's own statuses declare (the same
 * label a person sees in the web app for that Workflow) when the caller has that Workflow's
 * definition in hand; falls back to Title Case when it does not, rather than fetching a Workflow
 * just to label one field.
 */
export function statusLabel(status: string | undefined | null, statuses?: unknown[]): string | undefined {
  if (!status) return undefined
  if (Array.isArray(statuses)) {
    const match = statuses.find(
      (s) => s != null && typeof s === 'object' && (s as { id?: unknown }).id === status
    ) as { label?: unknown } | undefined
    if (match && typeof match.label === 'string' && match.label.trim()) return match.label
  }
  return titleCase(status)
}
