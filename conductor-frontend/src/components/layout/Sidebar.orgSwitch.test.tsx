import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const pushMock = vi.fn()
const setActiveOrgMock = vi.fn()
let pathnameValue = '/app/projects/proj-1/issues'

vi.mock('next/navigation', () => ({
  usePathname: () => pathnameValue,
  useRouter: () => ({ push: pushMock }),
}))

vi.mock('@/contexts/SidebarContext', () => ({
  useSidebar: () => ({
    isOpen: false,
    closeSidebar: vi.fn(),
    sidebarWidth: 240,
    setSidebarWidth: vi.fn(),
  }),
}))

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => ({
    projects: [{ id: 'proj-1', name: 'Sweat Project', orgId: 'org-1' }],
    activeProject: { id: 'proj-1', name: 'Sweat Project', orgId: 'org-1' },
    setActiveProject: vi.fn(),
  }),
}))

vi.mock('@/contexts/OrgContext', () => ({
  useOrg: () => ({
    activeOrg: { id: 'org-1', name: 'AI Sweat Shop', slug: 'ai-sweat-shop', createdAt: '2024-01-01' },
    orgs: [
      { id: 'org-1', name: 'AI Sweat Shop', slug: 'ai-sweat-shop', createdAt: '2024-01-01' },
      { id: 'org-2', name: 'Rexcipe', slug: 'rexcipe', createdAt: '2024-01-02' },
    ],
    teams: [],
    loading: false,
    needsOnboarding: false,
    refetch: vi.fn(),
    setActiveOrg: setActiveOrgMock,
  }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'user-1', name: 'Test User', email: 'test@example.com', avatarUrl: null, displayName: null },
    accessToken: 'test-token',
    loading: false,
    signIn: vi.fn(),
    signOut: vi.fn(),
  }),
}))

import { Sidebar } from './Sidebar'

describe('Sidebar org switcher', () => {
  beforeEach(() => {
    pushMock.mockClear()
    setActiveOrgMock.mockClear()
    pathnameValue = '/app/projects/proj-1/issues'
  })

  it('switches org and navigates off project page when a different org is selected', async () => {
    const user = userEvent.setup()
    render(<Sidebar />)

    await user.click(screen.getByRole('button', { name: /test user/i }))
    await user.click(await screen.findByText('Rexcipe'))

    expect(setActiveOrgMock).toHaveBeenCalledWith(expect.objectContaining({ id: 'org-2' }))
    expect(pushMock).toHaveBeenCalledWith('/app/org/members')
  })

  it('does not navigate when the active org is re-selected', async () => {
    const user = userEvent.setup()
    render(<Sidebar />)

    await user.click(screen.getByRole('button', { name: /test user/i }))
    const items = await screen.findAllByText('AI Sweat Shop')
    await user.click(items[items.length - 1])

    expect(setActiveOrgMock).not.toHaveBeenCalled()
    expect(pushMock).not.toHaveBeenCalled()
  })

  it('switches org without navigating when not on a project-scoped page', async () => {
    pathnameValue = '/app/org/teams'
    const user = userEvent.setup()
    render(<Sidebar />)

    await user.click(screen.getByRole('button', { name: /test user/i }))
    await user.click(await screen.findByText('Rexcipe'))

    expect(setActiveOrgMock).toHaveBeenCalledWith(expect.objectContaining({ id: 'org-2' }))
    expect(pushMock).not.toHaveBeenCalled()
  })

  it('switches org and navigates when on a nested project settings page (e.g. settings/members)', async () => {
    pathnameValue = '/app/projects/proj-1/settings/members'
    const user = userEvent.setup()
    render(<Sidebar />)

    await user.click(screen.getByRole('button', { name: /test user/i }))
    await user.click(await screen.findByText('Rexcipe'))

    expect(setActiveOrgMock).toHaveBeenCalledWith(expect.objectContaining({ id: 'org-2' }))
    // org-2 has no projects in this mock, so falls back to org members page
    expect(pushMock).toHaveBeenCalledWith('/app/org/members')
  })
})
