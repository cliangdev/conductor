import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'

vi.mock('next/navigation', () => ({
  usePathname: () => '/app/projects/proj-1/settings/members',
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/lib/api', () => ({ apiGet: vi.fn() }))

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

import { apiGet } from '@/lib/api'
import { Sidebar } from './Sidebar'

function workflow(overrides: Record<string, unknown>) {
  return {
    id: 'wf',
    projectId: 'proj-1',
    name: 'ENGINEERING',
    enabled: true,
    kind: 'LIFECYCLE',
    sidebarEnabled: true,
    area: 'ENGINEERING',
    slug: 'ENGINEERING',
    noun: 'Issue',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('Sidebar', () => {
  beforeEach(() => {
    // Clear the module-scope sidebar cache between tests to prevent cross-test contamination.
    clearAllSidebarCaches()
    ;(apiGet as Mock).mockResolvedValue([])
  })

  it('renders the active workspace name in the quiet switcher', () => {
    render(<Sidebar />)
    expect(screen.getByText('Test Workspace')).toBeInTheDocument()
  })

  it('renders the workspace nav links to single top-level homes', () => {
    render(<Sidebar />)
    // The "Issues" fallback no longer exists — dynamic nav only. Check always-visible links.
    expect(screen.getByRole('link', { name: /docs/i })).toHaveAttribute('href', '/app/projects/proj-1/docs')
    expect(screen.getByRole('link', { name: /workflows/i })).toHaveAttribute('href', '/app/projects/proj-1/workflows')
    expect(screen.getByRole('link', { name: /agents/i })).toHaveAttribute('href', '/app/projects/proj-1/agents')
    expect(screen.getByRole('link', { name: /integrations/i })).toHaveAttribute('href', '/app/projects/proj-1/integrations')
  })

  it('does not duplicate Workflows / Integrations / Agents under Settings', () => {
    render(<Sidebar />)
    const hrefs = screen.getAllByRole('link').map((l) => l.getAttribute('href'))
    expect(hrefs).not.toContain('/app/projects/proj-1/settings/workflows')
    expect(hrefs).not.toContain('/app/projects/proj-1/settings/integrations')
    expect(hrefs).not.toContain('/app/projects/proj-1/settings/agents')
    expect(screen.queryByRole('link', { name: /connect apps/i })).not.toBeInTheDocument()
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

  // ── COND-22: dynamic Work nav from sidebar-enabled lifecycle Workflows ──

  it('renders a single Issues entry linking to the area/noun path when ENGINEERING is the only sidebar workflow', async () => {
    (apiGet as Mock).mockResolvedValue([workflow({})])
    render(<Sidebar />)
    // Wait for the dynamic nav to resolve. The href is the workflow-scoped /{area}/{nouns} shape
    // (both segments lowercased).
    await waitFor(() =>
      expect(screen.getByRole('link', { name: /issues/i })).toHaveAttribute(
        'href',
        '/app/projects/proj-1/engineering/issues',
      ),
    )
  })

  it('shows no Work nav items (only a skeleton) when API returns no sidebar-enabled workflows', async () => {
    (apiGet as Mock).mockResolvedValue([])
    render(<Sidebar />)
    // The static "Issues" fallback is gone — no /issues link should ever appear.
    expect(screen.queryByRole('link', { name: /^issues$/i })).not.toBeInTheDocument()
    // Docs is always present regardless of workflow state.
    expect(screen.getByRole('link', { name: /docs/i })).toHaveAttribute('href', '/app/projects/proj-1/docs')
  })

  it('renders a second sidebar-enabled workflow under its own area section', async () => {
    (apiGet as Mock).mockResolvedValue([
      workflow({}),
      workflow({
        id: 'wf-sales',
        name: 'SALES',
        area: 'SALES_OPS',
        slug: 'SALES',
        noun: 'Deal',
        createdAt: '2026-02-01T00:00:00Z',
      }),
    ])
    render(<Sidebar />)
    const deals = await screen.findByRole('link', { name: /deals/i })
    // area "SALES_OPS" + noun "Deal" → /sales_ops/deals (both lowercased, noun pluralized).
    expect(deals).toHaveAttribute('href', '/app/projects/proj-1/sales_ops/deals')
    // Area slug is humanized into a section label.
    expect(screen.getByText('Sales Ops')).toBeInTheDocument()
  })
})
