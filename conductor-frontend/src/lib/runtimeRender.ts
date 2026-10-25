import type { RuntimeTarget } from '@/lib/api'

export type RenderAvailability = { available: true } | { available: false; reason: string }

/**
 * Whether Creatives actually render on this target. Mirrors the backend's dispatch rule
 * (designated target ACTIVE + GCP connection + render job READY); anything else falls back to the
 * self-hosted daemon, and `reason` says which condition failed.
 */
export function renderAvailability(target: RuntimeTarget): RenderAvailability {
  const render = target.render ?? { status: 'NONE' as const }
  if (render.status === 'ERROR') return { available: false, reason: render.error || 'Render setup failed.' }
  if (render.status !== 'READY') return { available: false, reason: 'Not set up for rendering' }
  if (target.status !== 'ACTIVE') return { available: false, reason: `Target is ${target.status.toLowerCase()}` }
  if (!target.connectionId) return { available: false, reason: 'No GCP connection' }
  return { available: true }
}
