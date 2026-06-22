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
    sidebarCollapsed: false,
    setSidebarCollapsed: vi.fn(),
  }),
}))

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => ({
    projects: [{ id: 'proj-1', name: 'Test Workspace' }],
    activeProject: { id: 'proj-1', name: 'Test Workspace' },
    setActiveProject: vi.fn(),
  }),
}))

vi.mock('@/contexts/EditorChromeContext', () => ({
  useEditorChrome: () => ({
    fullscreen: false,
    setFullscreen: vi.fn(),
    docsTreeCollapsed: false,
    setDocsTreeCollapsed: vi.fn(),
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
  it('renders the active workspace name in the quiet switcher', () => {
    render(<Sidebar />)
    expect(screen.getByText('Test Workspace')).toBeInTheDocument()
  })

  it('renders the workspace nav links', () => {
    render(<Sidebar />)
    expect(screen.getByRole('link', { name: /issues/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /docs/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /workflows/i })).toBeInTheDocument()
  })

  it('renders Settings group when on a workspace settings page', () => {
    render(<Sidebar />)
    expect(screen.getAllByText('Settings').length).toBeGreaterThanOrEqual(1)
  })

  it('renders General link under Settings pointing to settings/general', () => {
    render(<Sidebar />)
    const generalLink = screen.getByRole('link', { name: /general/i })
    expect(generalLink).toHaveAttribute('href', '/app/projects/proj-1/settings/general')
  })

  it('renders Members link under Settings pointing to settings/members', () => {
    render(<Sidebar />)
    const membersLinks = screen.getAllByRole('link', { name: /members/i })
    const settingsMembersLink = membersLinks.find((l) =>
      l.getAttribute('href')?.includes('settings/members')
    )
    expect(settingsMembersLink).toHaveAttribute('href', '/app/projects/proj-1/settings/members')
  })

  it('renders API Keys link pointing to workspace settings/api-keys', () => {
    render(<Sidebar />)
    const apiKeysLink = screen.getByRole('link', { name: /api keys/i })
    expect(apiKeysLink).toHaveAttribute('href', '/app/projects/proj-1/settings/api-keys')
  })

  it('renders Notifications link under Settings', () => {
    render(<Sidebar />)
    const notificationsLink = screen.getByRole('link', { name: /notifications/i })
    expect(notificationsLink).toHaveAttribute('href', '/app/projects/proj-1/settings/notifications')
  })

  it('does not render a standalone GitHub settings link (folded into Integrations hub)', () => {
    render(<Sidebar />)
    expect(screen.queryByRole('link', { name: /github/i })).not.toBeInTheDocument()
  })

  it('does not render a Teams link', () => {
    render(<Sidebar />)
    expect(screen.queryByRole('link', { name: /^teams$/i })).not.toBeInTheDocument()
  })

  it('does not render a Visibility link', () => {
    render(<Sidebar />)
    expect(screen.queryByRole('link', { name: /visibility/i })).not.toBeInTheDocument()
  })

  it('does not link to any /app/org route', () => {
    render(<Sidebar />)
    const orgLinks = screen.queryAllByRole('link').filter((l) =>
      l.getAttribute('href')?.startsWith('/app/org')
    )
    expect(orgLinks).toHaveLength(0)
  })
})
