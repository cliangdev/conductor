import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  usePathname: () => '/app/projects/proj-1/settings/members',
  useRouter: () => ({ push: vi.fn() }),
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
    projects: [{ id: 'proj-1', name: 'Test Project' }],
    activeProject: { id: 'proj-1', name: 'Test Project' },
    setActiveProject: vi.fn(),
  }),
}))

vi.mock('@/contexts/OrgContext', () => ({
  useOrg: () => ({
    activeOrg: { id: 'org-1', name: 'Test Org', slug: 'test-org', createdAt: '2024-01-01' },
    orgs: [{ id: 'org-1', name: 'Test Org', slug: 'test-org', createdAt: '2024-01-01' }],
    teams: [],
    loading: false,
    needsOnboarding: false,
    refetch: vi.fn(),
    setActiveOrg: vi.fn(),
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

describe('Sidebar', () => {
  it('renders the active project name in the project switcher', () => {
    render(<Sidebar />)
    expect(screen.getByText('Test Project')).toBeInTheDocument()
  })

  it('renders Settings group when on a project settings page', () => {
    render(<Sidebar />)
    expect(screen.getAllByText('Settings').length).toBeGreaterThanOrEqual(1)
  })

  it('renders Members link under project Settings pointing to settings/members', () => {
    render(<Sidebar />)
    const membersLinks = screen.getAllByRole('link', { name: /members/i })
    const settingsMembersLink = membersLinks.find((l) =>
      l.getAttribute('href')?.includes('settings/members')
    )
    expect(settingsMembersLink).toBeInTheDocument()
    expect(settingsMembersLink).toHaveAttribute('href', '/app/projects/proj-1/settings/members')
  })

  it('renders API Keys link under Settings pointing to /app/settings/api-keys', () => {
    render(<Sidebar />)
    const apiKeysLink = screen.getByRole('link', { name: /api keys/i })
    expect(apiKeysLink).toBeInTheDocument()
    expect(apiKeysLink).toHaveAttribute('href', '/app/settings/api-keys')
  })

  it('renders Notifications link under Settings', () => {
    render(<Sidebar />)
    const notificationsLink = screen.getByRole('link', { name: /notifications/i })
    expect(notificationsLink).toBeInTheDocument()
    expect(notificationsLink).toHaveAttribute('href', '/app/projects/proj-1/settings/notifications')
  })

  it('renders Workspace section with Members and Settings links', () => {
    render(<Sidebar />)
    expect(screen.getByText('Workspace')).toBeInTheDocument()
    const allMembersLinks = screen.getAllByRole('link', { name: /members/i })
    const workspaceMembersLink = allMembersLinks.find((l) => l.getAttribute('href') === '/app/org/members')
    expect(workspaceMembersLink).toBeInTheDocument()
  })

  it('does not render Teams link in Workspace when org has no teams', () => {
    render(<Sidebar />)
    // Only check within workspace - no teams link should exist since teams=[]
    const teamsLinks = screen.queryAllByRole('link', { name: /^teams$/i })
    expect(teamsLinks).toHaveLength(0)
  })

  it('renders user name in footer', () => {
    render(<Sidebar />)
    expect(screen.getByText('Test User')).toBeInTheDocument()
  })
})
