// @cliangdev/creative-render ships plain ESM `.js` with no `.d.ts` (see its README) — these
// interfaces are a hand-kept TypeScript mirror of the "creative shape" and "brand shape" the
// package's own README documents (`mountBoard`, `renderBoard`, `resolveAd`, ...). Keep them in sync
// with that README when the package's shape changes; there is no compiler to catch drift.

export interface RenderMotion {
  preset?: 'fade-up' | 'word-by-word' | 'accent-pop' | 'none'
  durationSec?: number
  background?: {
    source?: 'photo' | 'clip'
    motion?: 'zoom-in' | 'zoom-out' | 'pan-left' | 'pan-right' | 'none'
  }
  endCard?: boolean
}

export interface RenderAudio {
  source?: 'clip' | 'track' | 'none'
  trackUrl?: string
  volume?: number
  fadeOutSec?: number
}

export interface RenderCreative {
  layout?: 'stacked' | 'bleed' | 'card' | 'split'
  theme?: 'dark' | 'light'
  lockup?: 'plain' | 'chip'
  headline: string
  body?: string
  caption?: string
  photoUrl?: string
  focal?: Record<string, string>
  focalOverride?: Record<string, string>
  placements?: string[]
  layoutOverrides?: {
    band?: Record<string, number> | null
    padBottom?: Record<string, number> | null
  } | null
  typeOverrides?: Record<string, number[]>
  sequenceKind?: 'story' | 'carousel' | null
  sequence?: Array<{
    headline?: string
    body?: string
    photoUrl?: string
    focal?: Record<string, string>
    cta?: boolean
  }>
  // MOTION only (creative.kind === 'MOTION') — see motion.js's Public API and mount.js's play/pause/
  // seek/onTime, which only activate when `motion` is present.
  kind?: 'STILL' | 'MOTION' | 'CLIP'
  motion?: RenderMotion
  audio?: RenderAudio
  /** A clip background's signed URL (`motion.background.source === 'clip'`). */
  backgroundVideoUrl?: string
  /** Where that clip starts, in seconds (`motion.background.clipStartSec`). */
  clipStartSec?: number
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
  /** MOTION preview only — a no-op (resolves immediately) for a STILL/CLIP creative (no
   *  `creative.motion`) or before the first board has mounted. See motion.js/mount.js. */
  seek(tSec: number): Promise<void>
  /** Plays the MOTION preview in real time, looping at `motion.durationSec`. No-op otherwise. */
  play(): void
  pause(): void
  /** Subscribes to every seek() (from play() or a manual scrub); returns an unsubscribe function. */
  onTime(cb: (tSec: number) => void): () => void
}

export interface MountOptions {
  creative: RenderCreative
  brand: RenderBrand
  placementKey: string
  scale?: number
  sequenceIndex?: number
}
