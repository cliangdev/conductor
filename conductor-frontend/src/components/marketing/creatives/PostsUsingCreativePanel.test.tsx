import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiErrorMessage: (_e: unknown, f: string) => f,
}))
const nav = vi.hoisted(() => ({ loading: false }))
vi.mock('@/lib/workflows', async (orig) => ({
  ...(await orig<typeof import('@/lib/workflows')>()),
  useSidebarWorkNav: () => ({
    entries: nav.loading ? [] : [{ slug: 'MARKETING', label: 'Posts', noun: 'Post', area: 'MARKETING', createdAt: '' }],
    loading: nav.loading,
  }),
}))

import { apiGet } from '@/lib/api'
import { PostsUsingCreativePanel } from './PostsUsingCreativePanel'

beforeEach(() => {
  vi.clearAllMocks()
  nav.loading = false
})

describe('PostsUsingCreativePanel', () => {
  it('lists the posts filtered by creativeId, each linking to the post', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'w1', displayId: 'RE-88', title: 'Pasta, 3 ways', status: 'SCHEDULED', statusLabel: 'Scheduled' },
    ])
    render(<PostsUsingCreativePanel projectId="p" creativeId="c1" token="t" />)
    const link = await screen.findByRole('link', { name: 'Pasta, 3 ways' })
    expect(link).toHaveAttribute('href', '/app/projects/p/marketing/posts/RE-88')
    expect(screen.getByText('Scheduled')).toBeInTheDocument()
    expect((apiGet as ReturnType<typeof vi.fn>).mock.calls[0]![0]).toBe('/api/v2/projects/p/work-items?creativeId=c1&workflow=MARKETING')
  })

  it('says so when nothing uses it', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    render(<PostsUsingCreativePanel projectId="p" creativeId="c1" token="t" />)
    expect(await screen.findByText('No posts use this creative yet.')).toBeInTheDocument()
  })

  it('asks the server for only the publishing workflow\'s posts', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    render(<PostsUsingCreativePanel projectId="p" creativeId="c1" token="t" />)
    await screen.findByText('No posts use this creative yet.')
    expect(String((apiGet as ReturnType<typeof vi.fn>).mock.calls[0]![0])).toContain('workflow=MARKETING')
  })

  it('does not wait on a skeleton forever when there is no token', async () => {
    render(<PostsUsingCreativePanel projectId="p" creativeId="c1" token="" />)
    expect(await screen.findByText('Posts using this creative cannot be listed right now.')).toBeInTheDocument()
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('clears the previous creative\'s posts and error when the creative changes', async () => {
    const get = apiGet as ReturnType<typeof vi.fn>
    get.mockRejectedValueOnce(new Error('boom'))
    const { rerender } = render(<PostsUsingCreativePanel projectId="p" creativeId="c1" token="t" />)
    expect(await screen.findByText('Could not load the posts using this creative')).toBeInTheDocument()

    let release: (v: unknown[]) => void = () => {}
    get.mockReturnValueOnce(new Promise((r) => (release = r)))
    rerender(<PostsUsingCreativePanel projectId="p" creativeId="c2" token="t" />)
    await waitFor(() => expect(screen.queryByText('Could not load the posts using this creative')).toBeNull())
    release([])
    expect(await screen.findByText('No posts use this creative yet.')).toBeInTheDocument()
  })

  it('shows the skeleton and asks for nothing until the workflow nav has resolved, then lists only that workflow\'s posts', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'w1', displayId: 'RE-1', title: 'A post', status: 'DRAFT', workflow: 'MARKETING' },
    ])
    nav.loading = true
    const { rerender } = render(<PostsUsingCreativePanel projectId="p" creativeId="c1" token="t" />)
    // Nothing is requested or rendered while the nav is unresolved: no flash of unlinked rows.
    expect(apiGet).not.toHaveBeenCalled()
    expect(screen.queryByText('A post')).toBeNull()
    expect(screen.queryByText('No posts use this creative yet.')).toBeNull()

    nav.loading = false
    rerender(<PostsUsingCreativePanel projectId="p" creativeId="c1" token="t" />)
    const link = await screen.findByRole('link', { name: 'A post' })
    expect(link).toHaveAttribute('href', '/app/projects/p/marketing/posts/RE-1')
    expect(apiGet).toHaveBeenCalledTimes(1)
  })
})
