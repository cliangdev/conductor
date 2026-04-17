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
    loading: false,
    needsOnboarding: false,
    refetch: vi.fn(),
  }),
}))

import { Sidebar } from './Sidebar'

describe('Sidebar', () => {
  it('renders Settings group when a project is active', () => {
    render(<Sidebar />)
    expect(screen.getByText('Settings')).toBeInTheDocument()
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

  it('renders Settings sub-links in order: Members, API Keys, Notifications', () => {
    render(<Sidebar />)
    const links = screen.getAllByRole('link')
    const linkTexts = links.map((l) => l.textContent?.trim())
    const membersIndex = linkTexts.findIndex((t) => t === 'Members')
    const apiKeysIndex = linkTexts.findIndex((t) => t === 'API Keys')
    const notificationsIndex = linkTexts.findIndex((t) => t === 'Notifications')
    expect(membersIndex).toBeGreaterThanOrEqual(0)
    expect(apiKeysIndex).toBeGreaterThan(membersIndex)
    expect(notificationsIndex).toBeGreaterThan(apiKeysIndex)
  })
})
