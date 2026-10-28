import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

const nav = vi.hoisted(() => ({ calls: [] as (string | undefined)[] }))
vi.mock('next/navigation', () => ({ usePathname: () => '/app/projects/p/marketing/media' }))
vi.mock('@/contexts/AuthContext', () => ({ useAuth: () => ({ accessToken: 'tok' }) }))
vi.mock('@/lib/workflows', async (orig) => ({
  ...(await orig<typeof import('@/lib/workflows')>()),
  useSidebarWorkNav: (projectId: string | undefined) => {
    nav.calls.push(projectId)
    return { entries: [{ slug: 'MARKETING', label: 'Posts', noun: 'Post', area: 'MARKETING', createdAt: '' }], loading: false }
  },
}))

import { ContentSubNav } from './ContentSubNav'

beforeEach(() => {
  nav.calls.length = 0
})

describe('ContentSubNav', () => {
  it('looks the Posts list up for the Creatives and Media pages, with the real project id', () => {
    render(<ContentSubNav projectId="p" />)
    expect(screen.getByRole('tab', { name: 'Posts' })).toHaveAttribute('href', '/app/projects/p/marketing/posts')
    expect(nav.calls).toEqual(['p'])
    expect(nav.calls).not.toContain('')
  })

  it('does no lookup at all when the Posts page passes its own list path', () => {
    render(<ContentSubNav projectId="p" active="posts" postsHref="/app/projects/p/engineering/posts" />)
    expect(screen.getByRole('tab', { name: 'Posts' })).toHaveAttribute('href', '/app/projects/p/engineering/posts')
    expect(nav.calls).toEqual([])
  })

  it('marks the current section from the page', () => {
    render(<ContentSubNav projectId="p" />)
    expect(screen.getByRole('tab', { name: 'Media' })).toHaveAttribute('aria-selected', 'true')
  })
})
