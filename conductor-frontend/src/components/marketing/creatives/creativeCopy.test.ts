import { describe, it, expect } from 'vitest'
import { canUseInPost, needsLocalRender, needsServerRender } from './creativeCopy'

describe('needsLocalRender / needsServerRender', () => {
  it('renders a CAROUSEL on the server unless it has a track', () => {
    expect(needsServerRender({ kind: 'CAROUSEL', audio: null })).toBe(true)
    expect(needsServerRender({ kind: 'CAROUSEL', audio: { source: 'none' } })).toBe(true)
    expect(needsServerRender({ kind: 'CAROUSEL', audio: { source: 'track', trackId: 't' } })).toBe(false)
    expect(needsLocalRender({ kind: 'CAROUSEL', audio: { source: 'track', trackId: 't' } })).toBe(true)
  })

  it('renders a CLIP on the server only when its sound is untouched', () => {
    expect(needsServerRender({ kind: 'CLIP', audio: null })).toBe(true)
    expect(needsServerRender({ kind: 'CLIP', audio: { source: 'clip' } })).toBe(true)
    for (const source of ['track', 'mix', 'none'] as const) {
      expect(needsServerRender({ kind: 'CLIP', audio: { source, trackId: 't' } })).toBe(false)
      expect(needsLocalRender({ kind: 'CLIP', audio: { source, trackId: 't' } })).toBe(true)
    }
  })

  it('never auto-renders STILL or MOTION on the server', () => {
    expect(needsServerRender({ kind: 'STILL', audio: null })).toBe(false)
    expect(needsServerRender({ kind: 'MOTION', audio: null })).toBe(false)
  })

  it('does not call a READY music carousel usable until it has a render', () => {
    const base = { state: 'READY' as const, latestRenderId: null }
    expect(canUseInPost({ ...base, kind: 'CAROUSEL', audio: null })).toBe(true)
    expect(canUseInPost({ ...base, kind: 'CAROUSEL', audio: { source: 'track', trackId: 't' } })).toBe(false)
    expect(canUseInPost({ ...base, kind: 'CLIP', audio: { source: 'mix', trackId: 't' } })).toBe(false)
    expect(canUseInPost({ ...base, latestRenderId: 'r1', kind: 'CAROUSEL', audio: { source: 'track', trackId: 't' } })).toBe(true)
  })
})
