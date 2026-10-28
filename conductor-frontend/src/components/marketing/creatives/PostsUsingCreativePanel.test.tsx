import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiErrorMessage: (_e: unknown, f: string) => f,
}))
vi.mock('@/lib/workflows', async (orig) => ({
  ...(await orig<typeof import('@/lib/workflows')>()),
  useSidebarWorkNav: () => ({ entries: [{ slug: 'MARKETING', label: 'Posts', noun: 'Post', area: 'MARKETING', createdAt: '' }], loading: false }),
}))

import { apiGet } from '@/lib/api'
import { PostsUsingCreativePanel } from './PostsUsingCreativePanel'

beforeEach(() => vi.clearAllMocks())

describe('PostsUsingCreativePanel', () => {
  it('lists the posts filtered by creativeId, each linking to the post', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'w1', displayId: 'RE-88', title: 'Pasta, 3 ways', status: 'SCHEDULED', statusLabel: 'Scheduled' },
    ])
    render(<PostsUsingCreativePanel projectId="p" creativeId="c1" token="t" />)
    const link = await screen.findByRole('link', { name: 'Pasta, 3 ways' })
    expect(link).toHaveAttribute('href', '/app/projects/p/marketing/posts/RE-88')
    expect(screen.getByText('Scheduled')).toBeInTheDocument()
    expect((apiGet as ReturnType<typeof vi.fn>).mock.calls[0]![0]).toBe('/api/v2/projects/p/work-items?creativeId=c1')
  })

  it('says so when nothing uses it', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    render(<PostsUsingCreativePanel projectId="p" creativeId="c1" token="t" />)
    expect(await screen.findByText('No posts use this creative yet.')).toBeInTheDocument()
  })
})
