import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'

vi.mock('@/components/marketing/creatives/types', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/marketing/creatives/types')>()),
  listCreativePhotosByIds: vi.fn(),
}))

import { listCreativePhotosByIds, type CreativePhoto } from '@/components/marketing/creatives/types'
import { tileMediaIds, useMediaByIds } from './useMediaByIds'
import { creative } from './creativeFixtures'

const p = (id: string) => ({ id }) as CreativePhoto

describe('tileMediaIds', () => {
  it('collects only what thumbnail-less tiles need, de-duplicated and sorted', () => {
    const ids = tileMediaIds([
      creative('1a', { kind: 'CLIP', latestRenderThumbnailUrl: null, clipMedia: { default: 'z-clip' } }),
      creative('2a', { kind: 'CLIP', latestRenderThumbnailUrl: 'https://t/x.jpg', clipMedia: { default: 'rendered' } }),
      creative('3a', { kind: 'CAROUSEL', latestRenderThumbnailUrl: null, slides: { '4x5': ['s1', 's2'] } }),
      creative('4a', { kind: 'CLIP', latestRenderThumbnailUrl: null, clipMedia: { default: 'z-clip' } }),
    ])
    expect(ids).toEqual(['s1', 'z-clip'])
  })
})

describe('useMediaByIds', () => {
  beforeEach(() => vi.clearAllMocks())

  it('fetches the ids once and only the newly missing ones afterwards', async () => {
    ;(listCreativePhotosByIds as Mock).mockImplementation(async (_p: string, _t: string, ids: string[]) => ids.map(p))
    const { result, rerender } = renderHook(({ ids }) => useMediaByIds('proj', 'tok', ids), {
      initialProps: { ids: ['a', 'b'] },
    })
    await waitFor(() => expect(result.current.size).toBe(2))
    rerender({ ids: ['a', 'b', 'c'] })
    await waitFor(() => expect(result.current.size).toBe(3))
    expect(listCreativePhotosByIds).toHaveBeenCalledTimes(2)
    expect((listCreativePhotosByIds as Mock).mock.calls[1][2]).toEqual(['c'])
  })

  it('makes no request for an empty id list', () => {
    renderHook(() => useMediaByIds('proj', 'tok', []))
    expect(listCreativePhotosByIds).not.toHaveBeenCalled()
  })
})
