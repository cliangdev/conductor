import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'

vi.mock('@/components/marketing/creatives/types', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/marketing/creatives/types')>()),
  listCreatives: vi.fn(),
}))

import { listCreatives } from '@/components/marketing/creatives/types'
import { useCreatives } from './CreativesTab'

describe('useCreatives', () => {
  beforeEach(() => vi.clearAllMocks())

  it('does not fetch until a token exists', async () => {
    ;(listCreatives as Mock).mockResolvedValue([])
    const { rerender } = renderHook(({ token }) => useCreatives('p', token), { initialProps: { token: '' } })
    expect(listCreatives).not.toHaveBeenCalled()
    rerender({ token: 'tok' })
    await waitFor(() => expect(listCreatives).toHaveBeenCalledTimes(1))
  })
})
