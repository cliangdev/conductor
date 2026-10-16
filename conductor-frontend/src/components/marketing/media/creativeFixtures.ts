import type { Creative, CreativeRender, CreativeRenderFrame } from '@/components/marketing/creatives/types'

export function frame(placementKey: string, sequenceIndex: number | null, extra: Partial<CreativeRenderFrame> = {}): CreativeRenderFrame {
  return {
    id: `f-${placementKey}-${sequenceIndex}`,
    placementKey,
    sequenceIndex,
    url: `https://storage.example/${placementKey}-${sequenceIndex}.png`,
    width: 1080,
    height: 1350,
    sizeBytes: 100,
    warnings: [],
    contentType: 'image/png',
    ...extra,
  }
}

export function creative(displayId: string, extra: Partial<Creative> = {}): Creative {
  return {
    id: `cr-${displayId}`,
    projectId: 'proj-1',
    brandKitId: 'bk-1',
    number: 18,
    variantLetter: 'a',
    displayId,
    name: `Creative ${displayId}`,
    state: 'READY',
    kind: 'STILL',
    layout: 'hero',
    theme: 'dark',
    placements: ['9x16', '4x5', '1x1'],
    sequence: [],
    typeOverrides: {},
    lockup: 'plain',
    version: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    latestRenderId: `render-${displayId}`,
    latestRenderThumbnailUrl: `https://storage.example/${displayId}-thumb.png`,
    ...extra,
  } as Creative
}

export function render(id: string, frames: CreativeRenderFrame[], extra: Partial<CreativeRender> = {}): CreativeRender {
  return { id, state: 'SUCCEEDED', previewOnly: false, creativeVersion: 1, requestedAt: '2026-01-01T00:00:00Z', frames, ...extra }
}

/** A 3-slide carousel in three sizes, as 18a is on Rexipe. */
export const CAROUSEL_FRAMES = ['9x16', '4x5', '1x1'].flatMap((k) => [0, 1, 2].map((i) => frame(k, i)))
