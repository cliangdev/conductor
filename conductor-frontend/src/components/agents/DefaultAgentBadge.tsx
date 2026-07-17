import { Badge } from '@/components/ui/badge'

/**
 * Marks an agent seeded by Conductor itself (e.g. the knowledge-librarian) rather than created by a
 * project member. Neutral chip — chrome is neutral, state is color (design-system.md) — so this uses
 * the plain `secondary` variant, never a status hue.
 */
export function DefaultAgentBadge() {
  return (
    <Badge variant="secondary" title="Seeded by Conductor — recreated if deleted">
      Default
    </Badge>
  )
}
