// Explains a MISMATCH channel: the channel's own connection and a second source disagree beyond the band
// (1 unit under 50, 2% above that and for money) over the days both have finished reporting. Lists every
// disagreeing source with both figures, the days and the source's name, and points to the verification ledger.
// The card links here through the #mismatch anchor.

import Link from 'next/link'
import { Alert } from '@/components/ui/alert'
import { formatMetricValue, mismatchCardLine, mismatchSummary, shortDate } from './format'
import type { MetricUnit, OverviewChannel, OverviewMismatch } from './types'

/** Every disagreement of a channel: the list when the API sends one, else the single `mismatch` (older servers). */
export function mismatchEntries(channel: OverviewChannel): OverviewMismatch[] {
  if (channel.mismatches && channel.mismatches.length > 0) return channel.mismatches
  return channel.mismatch ? [channel.mismatch] : []
}

/** One disagreement in words. A backend figure reads as before; a platform read names where it was read. */
export function mismatchText(m: OverviewMismatch, channelLabel: string): string {
  if (m.kind !== 'PLATFORM') return mismatchSummary(m, channelLabel)
  const unit: MetricUnit = m.metric === 'spend' ? 'CURRENCY' : 'COUNT'
  const noun = m.label.toLowerCase()
  const own = formatMetricValue(m.channelValue, unit)
  const amount = unit === 'CURRENCY' ? `${own} in ${noun}` : `${own} ${noun}`
  const read = m.referenceSource ? `the platform read from ${m.referenceSource}` : 'the platform read'
  const range = m.from === m.to ? shortDate(m.from) : `${shortDate(m.from)} – ${shortDate(m.to)}`
  return `${m.channelSource || channelLabel} reports ${amount}; ${read} reports ${formatMetricValue(m.referenceValue, unit)} — ${(m.gapPct * 100).toFixed(1)}% apart for ${range}, the days both have finished reporting.`
}

/** The card's one line: the first disagreement, and how many more there are. */
export function mismatchCardText(channel: OverviewChannel): string | null {
  const [first, ...rest] = mismatchEntries(channel)
  if (!first) return null
  const line = first.kind === 'PLATFORM' && !first.referenceSource
    ? `${first.label} differ from the platform read by ${(first.gapPct * 100).toFixed(1)}%`
    : mismatchCardLine(first)
  return rest.length > 0 ? `${line} (+${rest.length} more)` : line
}

export function MismatchNotice({ channel, projectId }: { channel: OverviewChannel; projectId?: string }) {
  const entries = mismatchEntries(channel)
  if (channel.status !== 'MISMATCH' || entries.length === 0) return null
  return (
    <div id="mismatch" className="scroll-mt-24">
      <Alert variant="warning" aria-label="Numbers do not match">
        <div>
          {entries.length === 1 ? (
            <p className="font-medium">{mismatchText(entries[0], channel.label)}</p>
          ) : (
            <ul className="list-disc space-y-1 pl-4 font-medium">
              {entries.map((m, i) => (
                <li key={`${m.kind ?? 'BACKEND'}-${m.metric}-${m.referenceSource ?? ''}-${i}`}>{mismatchText(m, channel.label)}</li>
              ))}
            </ul>
          )}
          <p className="mt-1 text-sm">
            This usually means one side is missing days or counts days in a different time zone. Check both
            connections&apos; last sync, then compare a single day.
          </p>
          {projectId && (
            <p className="mt-1 text-sm">
              <Link
                prefetch={false}
                href={`/app/projects/${projectId}/marketing/verification`}
                className="rounded-sm font-medium underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                Open the verification ledger
              </Link>{' '}
              to see which figure to trust.
            </p>
          )}
        </div>
      </Alert>
    </div>
  )
}
