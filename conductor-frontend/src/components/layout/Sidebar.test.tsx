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

import { Sidebar } from './Sidebar'

describe('Sidebar', () => {
  it('renders Settings group when a project is active', () => {
    render(<Sidebar />)
    expect(screen.getByText('Settings')).toBeInTheDocument()
  })

  it('renders Members link under Settings pointing to settings/members', () => {
    render(<Sidebar />)
    const membersLink = screen.getByRole('link', { name: /members/i })
    expect(membersLink).toBeInTheDocument()
    expect(membersLink).toHaveAttribute('href', '/app/projects/proj-1/settings/members')
  })

  it('renders Tools link under Settings pointing to settings/tools', () => {
    render(<Sidebar />)
    const toolsLink = screen.getByRole('link', { name: /tools/i })
    expect(toolsLink).toBeInTheDocument()
    expect(toolsLink).toHaveAttribute('href', '/app/projects/proj-1/settings/tools')
  })

  it('renders Notifications link under Settings pointing to settings/notifications', () => {
    render(<Sidebar />)
    const notificationsLink = screen.getByRole('link', { name: /notifications/i })
    expect(notificationsLink).toBeInTheDocument()
    expect(notificationsLink).toHaveAttribute('href', '/app/projects/proj-1/settings/notifications')
  })

  it('renders Settings sub-links in order: Members, Tools, Notifications', () => {
    render(<Sidebar />)
    const links = screen.getAllByRole('link')
    const linkTexts = links.map((l) => l.textContent?.trim())
    const membersIndex = linkTexts.findIndex((t) => t === 'Members')
    const toolsIndex = linkTexts.findIndex((t) => t === 'Tools')
    const notificationsIndex = linkTexts.findIndex((t) => t === 'Notifications')
    expect(membersIndex).toBeGreaterThanOrEqual(0)
    expect(toolsIndex).toBeGreaterThan(membersIndex)
    expect(notificationsIndex).toBeGreaterThan(toolsIndex)
  })
})
