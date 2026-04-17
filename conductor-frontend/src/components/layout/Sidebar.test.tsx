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

import { Sidebar } from './Sidebar'

describe('Sidebar', () => {
  it('renders Settings group when on a project settings page', () => {
    render(<Sidebar />)
    // Both project Settings nav group and Workspace Settings link exist
    expect(screen.getAllByText('Settings').length).toBeGreaterThanOrEqual(1)
  })

  it('renders Members link under Settings pointing to settings/members', () => {
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

  it('renders Notifications link under Settings pointing to settings/notifications', () => {
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

  it('does not render Teams section when org has no teams', () => {
    render(<Sidebar />)
    expect(screen.queryByText('Teams')).not.toBeInTheDocument()
  })
})
