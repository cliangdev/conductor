import { Badge } from '@/components/ui/badge'

/**
 * Marks an agent a human can talk to directly in a conversation (Discord's /ask, the conversation
 * REST API) by name or slug. Neutral chip — chrome is neutral, state is color (design-system.md) —
 * same `secondary` variant as `DefaultAgentBadge`, never a status hue.
 */
export function AddressableBadge() {
  return (
    <Badge variant="secondary" title="Can be talked to directly in a conversation (e.g. Discord /ask)">
      Addressable
    </Badge>
  )
}
