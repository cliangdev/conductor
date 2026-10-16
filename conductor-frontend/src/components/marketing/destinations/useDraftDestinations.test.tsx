import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { jsonResponse } from '@/components/marketing/test-fixtures'
import { useDraftDestinations } from './useDraftDestinations'

const fetchMock = vi.fn(async () => jsonResponse(200, []))

describe('hooks without a token', () => {
  afterEach(() => {
    fetchMock.mockClear()
    vi.unstubAllGlobals()
  })

  it('useDraftDestinations does not fetch until a token exists', async () => {
    vi.stubGlobal('fetch', fetchMock)
    const { rerender } = renderHook(({ token }) => useDraftDestinations({ projectId: 'p', token }), {
      initialProps: { token: '' },
    })
    expect(fetchMock).not.toHaveBeenCalled()
    rerender({ token: 'tok' })
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
  })
})
