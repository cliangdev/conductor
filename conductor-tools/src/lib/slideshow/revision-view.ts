/** How get_creative shows slide-text revisions (see mcp/tools/slideshow-text.ts for the tool that makes them). */

interface CheckView {
  slide?: number
  ratio?: string
  rule?: string
  severity?: string
  message?: string
}

/** Failing checks named by slide, ratio and rule; warnings are only counted. */
function compactChecks(checks: unknown): { failing: string[]; warnings: number } {
  const list = Array.isArray(checks) ? (checks as CheckView[]) : []
  const failing = list.filter((c) => c.severity === 'error').map((c) => `slide ${c.slide} ${c.ratio} ${c.rule}: ${c.message}`)
  return { failing: failing.slice(0, 10), warnings: list.filter((c) => c.severity !== 'error').length }
}

/**
 * What get_creative shows of the slide-text revisions: the latest one (status, error, failing checks by slide / ratio
 * / rule) and, when one is rendering, a pointer to it. Replaces the raw fields so the checks are not listed twice.
 */
export function compactRevisionState(creative: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  const latest = creative['latestRevision'] as (Record<string, unknown> & { checks?: unknown }) | null | undefined
  if (latest && typeof latest === 'object') {
    const { checks, ...rest } = latest
    const compact = compactChecks(checks)
    out['latestRevision'] = {
      ...rest,
      ...(compact.failing.length ? { failingChecks: compact.failing } : {}),
      ...(compact.warnings ? { warningCount: compact.warnings } : {}),
    }
  }
  const pending = creative['pendingRevision']
  if (pending && typeof pending === 'object') {
    out['pendingRevision'] = {
      ...(pending as Record<string, unknown>),
      note: 'A slide text edit is rendering; update_creative_slide_text is refused until it finishes. Call get_creative again to check.',
    }
  }
  return out
}
