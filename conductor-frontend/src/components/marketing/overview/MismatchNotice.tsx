// Explains a MISMATCH channel: the product backend's reference figure and this channel's own connector
// disagree by more than 1% over the same settled days. Shows both values and what to do about it.

import Link from 'next/link'
import { Alert } from '@/components/ui/alert'
import { mismatchSummary } from './format'
import type { OverviewChannel } from './types'

export function MismatchNotice({
  projectId,
  channel,
  sourceName = 'Product metrics',
}: {
  projectId: string
  channel: OverviewChannel
  /** What the reference figure comes from, e.g. the product metrics connection's name. */
  sourceName?: string
}) {
  const mismatch = channel.mismatch
  if (channel.status !== 'MISMATCH' || !mismatch) return null
  return (
    <Alert variant="warning" aria-label="Numbers do not match">
      <div>
      <p className="font-medium">{mismatchSummary(mismatch, sourceName)}</p>
      <p className="mt-1 text-sm">
        The two sources should agree once recent days settle. Check that {sourceName} and the {channel.label}{' '}
        connection point at the same app or account and cover the same dates, then wait for the next sync. If the gap
        stays, trust the {channel.label} connection for this channel and fix {sourceName}.{' '}
        <Link
          href={`/app/projects/${projectId}/integrations/product-metrics`}
          className="rounded-sm font-medium text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          Open product metrics
        </Link>
      </p>
      </div>
    </Alert>
  )
}
