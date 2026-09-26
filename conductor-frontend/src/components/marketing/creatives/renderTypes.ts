// @cliangdev/creative-render ships plain ESM `.js` with no `.d.ts` (see its README) — these
// interfaces are a hand-kept TypeScript mirror of the "creative shape" and "brand shape" the
// package's own README documents (`mountBoard`, `renderBoard`, `resolveAd`, ...). Keep them in sync
// with that README when the package's shape changes; there is no compiler to catch drift.

export interface RenderCreative {
  layout?: 'stacked' | 'bleed' | 'card' | 'split'
  theme?: 'dark' | 'light'
  headline: string
  body?: string
  caption?: string
  photoUrl?: string
  focal?: Record<string, string>
  focalOverride?: Record<string, string>
  placements?: string[]
  typeOverrides?: Record<string, number[]>
  sequenceKind?: 'story' | 'carousel' | null
  sequence?: Array<{
    headline?: string
    body?: string
    photoUrl?: string
    focal?: Record<string, string>
    cta?: boolean
  }>
}

export interface RenderBrand {
  tokens?: {
    accent?: string
    accent2?: string
    darkBg?: string
    darkInk?: string
    lightBg?: string
    lightCard?: string
    ink?: string
    ink2?: string
  }
  fontFamily?: string
  fontUrl?: string
  logos?: {
    mark?: string
    wordmarkDark?: string
    wordmarkLight?: string
    badge?: string
  }
  ctaClaim?: string
  enabledPlacements?: string[]
}

export interface MountHandle {
  ready: Promise<void>
  board: HTMLElement | null
  shell: HTMLElement
  update(next: Partial<MountOptions>): Promise<void>
  destroy(): void
}

export interface MountOptions {
  creative: RenderCreative
  brand: RenderBrand
  placementKey: string
  scale?: number
  sequenceIndex?: number
}
