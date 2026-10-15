import { StatusBadge, type StatusHue } from '@/components/ui/status-badge'
import type { ChannelStatus } from './types'

const STATUS: Record<ChannelStatus, { label: string; hue: StatusHue }> = {
  LIVE: { label: 'Live', hue: 'green' },
  STALE: { label: 'Stale', hue: 'amber' },
  NOT_CONNECTED: { label: 'Not connected', hue: 'gray' },
  COMING_SOON: { label: 'Coming soon', hue: 'slate' },
}

export function ChannelStatusPill({ status }: { status: ChannelStatus }) {
  const { label, hue } = STATUS[status]
  return <StatusBadge status={status} label={label} hue={hue} />
}
