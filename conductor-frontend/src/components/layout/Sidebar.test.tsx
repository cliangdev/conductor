import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'

const mockPush = vi.fn()
const mockCloseSidebar = vi.fn()
const mockSetPaletteOpen = vi.fn()
const mockSignOut = vi.fn().mockResolvedValue(undefined)
const mockSetTheme = vi.fn()

vi.mock('next/navigation', () => ({
  usePathname: () => '/app/projects/proj-1/settings/members',
  useRouter: () => ({ push: mockPush }),
}))

vi.mock('@/lib/api', () => ({ apiGet: vi.fn() }))

vi.mock('@/contexts/SidebarContext', () => ({
  useSidebar: () => ({
    isOpen: false,
    toggleSidebar: vi.fn(),
    closeSidebar: mockCloseSidebar,
    sidebarWidth: 240,
    setSidebarWidth: vi.fn(),
    sidebarCollapsed: false,
    setSidebarCollapsed: vi.fn(),
    paletteOpen: false,
    setPaletteOpen: mockSetPaletteOpen,
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
    signOut: mockSignOut,
  }),
}))

// Defaults to ADMIN (all capabilities) so existing tests see the full nav; permission-gating tests
// override this per-test.
let mockRole: 'ADMIN' | 'CREATOR' | 'REVIEWER' = 'ADMIN'
vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({
    role: mockRole,
    loading: false,
    can: (capability: string) => {
      if (mockRole === 'REVIEWER') return false
      if (mockRole === 'ADMIN') return true
      // CREATOR holds every capability except the admin-only set.
      return !['workspace.manage', 'members.manage', 'notifications.manage'].includes(capability)
    },
    refresh: vi.fn(),
  }),
}))

vi.mock('next-themes', () => ({
  useTheme: () => ({ theme: 'light', setTheme: mockSetTheme }),
}))

// Flatten the dropdown so trigger-gated content is always visible/clickable in jsdom (same
// approach as RuntimeTargetsPanel.test.tsx — the real Radix popup is portal-based).
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuItem: ({ children, onSelect }: { children: React.ReactNode; onSelect?: () => void }) => (
    <button type="button" onClick={onSelect}>{children}</button>
  ),
  DropdownMenuLabel: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuSeparator: () => <hr />,
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
    mockPush.mockClear()
    mockCloseSidebar.mockClear()
    mockSetPaletteOpen.mockClear()
    mockSignOut.mockClear()
    mockSetTheme.mockClear()
    mockRole = 'ADMIN'
  })

  it('renders the active workspace name in the quiet switcher', () => {
    render(<Sidebar />)
    expect(screen.getByText('Test Workspace')).toBeInTheDocument()
  })

  it('renders the workspace nav links to single top-level homes', () => {
    render(<Sidebar />)
    // The "Issues" fallback no longer exists — dynamic nav only. Check always-visible links.
    expect(screen.getByRole('link', { name: /docs/i })).toHaveAttribute('href', '/app/projects/proj-1/docs')
    expect(screen.getByRole('link', { name: /^knowledge$/i })).toHaveAttribute('href', '/app/projects/proj-1/knowledge')
    expect(screen.getByRole('link', { name: /^memory$/i })).toHaveAttribute('href', '/app/projects/proj-1/memory')
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

  // ── COND-290: Settings is a door, not a tree ──────────────────────────────

  it('renders exactly one Settings row linking to the settings door, with no sub-links inline', () => {
    render(<Sidebar />)
    const settingsLinks = screen.getAllByRole('link', { name: /^settings$/i })
    expect(settingsLinks).toHaveLength(1)
    expect(settingsLinks[0]).toHaveAttribute('href', '/app/projects/proj-1/settings')

    // None of the settings section labels appear as sidebar links anymore — they live in the
    // settings area's own sub-nav rail now.
    expect(screen.queryByRole('link', { name: /^general$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /members & roles/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /^api keys$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /^notifications$/i })).not.toBeInTheDocument()
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

  // ── COND-290 acceptance: compact rail + no admin-only entries for a REVIEWER ──────────

  it('shows 8 or fewer top-level sidebar rows with no sidebar-enabled Work nav', () => {
    render(<Sidebar />)
    // Docs, Knowledge, Memory (Workspace) + Workflows, Agents, Integrations (Automation) + Settings door.
    expect(screen.getAllByRole('link').length).toBeLessThanOrEqual(8)
  })

  it('keeps the Settings door visible for a REVIEWER (General/Members/API Keys/Notifications/CLI stay reachable)', () => {
    mockRole = 'REVIEWER'
    render(<Sidebar />)
    expect(screen.getByRole('link', { name: /^settings$/i })).toBeInTheDocument()
  })

  // ── User account menu ──────────────────────────────────────────────────────

  it('shows the signed-in user name and the sign-out/theme items in the account menu', () => {
    render(<Sidebar />)
    expect(screen.getAllByText('Test User').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByRole('button', { name: /sign out/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^light$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^dark$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^system$/i })).toBeInTheDocument()
  })

  it('signs out and redirects to /login when Sign out is clicked', async () => {
    render(<Sidebar />)
    fireEvent.click(screen.getByRole('button', { name: /sign out/i }))
    await waitFor(() => {
      expect(mockSignOut).toHaveBeenCalled()
      expect(mockPush).toHaveBeenCalledWith('/login')
    })
  })

  it('calls setTheme when a theme item is selected from the account menu', () => {
    render(<Sidebar />)
    fireEvent.click(screen.getByRole('button', { name: /^dark$/i }))
    expect(mockSetTheme).toHaveBeenCalledWith('dark')
  })
})
