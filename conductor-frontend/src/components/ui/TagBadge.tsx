import { Badge } from '@/components/ui/badge'

/**
 * A free-text grouping tag on an agent or workflow. Neutral chip — chrome is neutral, state is color
 * (design-system.md) — so this uses the plain `secondary` variant, never a status hue.
 */
export function TagBadge({ tag }: { tag: string }) {
  return <Badge variant="secondary">{tag}</Badge>
}
