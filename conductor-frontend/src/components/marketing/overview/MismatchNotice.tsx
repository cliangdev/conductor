// Explains a MISMATCH channel: the channel's own connection and the product backend disagree by more
// than 1% over the days both have finished reporting. Names both sources, shows both figures and what to
// check. The card links here through the #mismatch anchor.

import { Alert } from '@/components/ui/alert'
import { mismatchSummary } from './format'
import type { OverviewChannel } from './types'

export function MismatchNotice({ channel }: { channel: OverviewChannel }) {
  const mismatch = channel.mismatch
  if (channel.status !== 'MISMATCH' || !mismatch) return null
  return (
    <div id="mismatch" className="scroll-mt-24">
      <Alert variant="warning" aria-label="Numbers do not match">
        <div>
          <p className="font-medium">{mismatchSummary(mismatch, channel.label)}</p>
          <p className="mt-1 text-sm">
            This usually means one side is missing days or counts days in a different time zone. Check both
            connections&apos; last sync, then compare a single day.
          </p>
        </div>
      </Alert>
    </div>
  )
}
