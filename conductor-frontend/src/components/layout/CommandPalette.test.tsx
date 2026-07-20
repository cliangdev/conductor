import { useState } from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'

const mockPush = vi.fn()
const mockCloseSidebar = vi.fn()
const mockSetPaletteOpen = vi.fn()
const mockSetTheme = vi.fn()
const mockSetActiveProject = vi.fn()

vi.mock('next/navigation', () => ({
  usePathname: () => '/app/projects/proj-1/engineering/issues',
  useRouter: () => ({ push: mockPush }),
}))

vi.mock('@/lib/api', () => ({ apiGet: vi.fn().mockResolvedValue([]) }))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

let mockRole: 'ADMIN' | 'CREATOR' | 'REVIEWER' = 'ADMIN'
vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({
    role: mockRole,
    loading: false,
    can: (capability: string) => {
      if (mockRole === 'REVIEWER') return false
      if (mockRole === 'ADMIN') return true
      return !['workspace.manage', 'members.manage', 'notifications.manage'].includes(capability)
    },
    refresh: vi.fn(),
  }),
}))

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => ({
    projects: [{ id: 'proj-1', name: 'Test Workspace' }],
    activeProject: { id: 'proj-1', name: 'Test Workspace' },
    setActiveProject: mockSetActiveProject,
  }),
}))

vi.mock('next-themes', () => ({
  useTheme: () => ({ theme: 'system', setTheme: mockSetTheme }),
}))

// Real per-instance state (not a fixed object) so opening/closing round-trips through the
// component under test exactly like the real SidebarContext would.
vi.mock('@/contexts/SidebarContext', () => ({
  useSidebar: () => {
    const [paletteOpen, setOpenState] = useState(false)
    return {
      paletteOpen,
      setPaletteOpen: (v: boolean) => {
        mockSetPaletteOpen(v)
        setOpenState(v)
      },
      closeSidebar: mockCloseSidebar,
    }
  },
}))

// The real @base-ui/react Dialog is portal/floating-ui based and depends on browser APIs jsdom
// doesn't provide (ResizeObserver, PointerEvent) — stand in with the same Root/Portal/Backdrop/
// Popup shape, replicating the two behaviors CommandPalette now relies on the primitive for:
// backdrop-click-to-close and dialog-level Escape-to-close.
vi.mock('@base-ui/react/dialog', async () => {
  const React = await import('react')
  const DialogCtx = React.createContext<{ onOpenChange?: (open: boolean) => void }>({})

  function Root({
    open,
    onOpenChange,
    children,
  }: {
    open: boolean
    onOpenChange?: (open: boolean) => void
    children: React.ReactNode
  }) {
    return open ? <DialogCtx.Provider value={{ onOpenChange }}>{children}</DialogCtx.Provider> : null
  }
  function Portal({ children }: { children: React.ReactNode }) {
    return <>{children}</>
  }
  function Backdrop(props: React.HTMLAttributes<HTMLDivElement>) {
    const { onOpenChange } = React.useContext(DialogCtx)
    return <div data-testid="backdrop" onClick={() => onOpenChange?.(false)} {...props} />
  }
  function Popup({
    children,
    initialFocus: _initialFocus,
    ...rest
  }: React.HTMLAttributes<HTMLDivElement> & { initialFocus?: unknown }) {
    const { onOpenChange } = React.useContext(DialogCtx)
    React.useEffect(() => {
      function onKeyDown(e: KeyboardEvent) {
        if (e.key === 'Escape') onOpenChange?.(false)
      }
      document.addEventListener('keydown', onKeyDown)
      return () => document.removeEventListener('keydown', onKeyDown)
    }, [onOpenChange])
    return (
      <div role="dialog" {...rest}>
        {children}
      </div>
    )
  }
  return { Dialog: { Root, Portal, Backdrop, Popup } }
})

import { CommandPalette, registerPaletteActions } from './CommandPalette'

async function openPalette() {
  fireEvent.keyDown(window, { key: 'k', metaKey: true })
  return screen.findByRole('combobox')
}

describe('CommandPalette', () => {
  beforeEach(() => {
    clearAllSidebarCaches()
    mockPush.mockClear()
    mockCloseSidebar.mockClear()
    mockSetPaletteOpen.mockClear()
    mockSetTheme.mockClear()
    mockRole = 'ADMIN'
  })

  it('opens via the ⌘K keyboard shortcut', async () => {
    render(<CommandPalette />)
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
    await openPalette()
    expect(screen.getByRole('combobox')).toBeInTheDocument()
  })

  it('ignores ⌘K when another handler already claimed the keystroke (defaultPrevented)', () => {
    render(<CommandPalette />)
    const blocker = (e: KeyboardEvent) => e.preventDefault()
    window.addEventListener('keydown', blocker, true)
    fireEvent.keyDown(window, { key: 'k', metaKey: true })
    window.removeEventListener('keydown', blocker, true)
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  it('filters actions as you type', async () => {
    render(<CommandPalette />)
    const input = await openPalette()
    fireEvent.change(input, { target: { value: 'notif' } })
    expect(await screen.findByRole('option', { name: /notifications/i })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /^docs$/i })).not.toBeInTheDocument()
  })

  it('ArrowDown then Enter runs the second action and closes the palette', async () => {
    render(<CommandPalette />)
    const input = await openPalette()
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => {
      // Navigation group order is [Docs, Knowledge, ...]; ArrowDown from index 0 lands on Knowledge.
      expect(mockPush).toHaveBeenCalledWith('/app/projects/proj-1/knowledge')
    })
    expect(mockCloseSidebar).toHaveBeenCalled()
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument()
  })

  it('closes on Escape', async () => {
    render(<CommandPalette />)
    await openPalette()
    fireEvent.keyDown(document, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('combobox')).not.toBeInTheDocument())
  })

  // ── COND-290: permission-gated Settings entries ────────────────────────────

  it('lists AI Providers and Secrets for an admin', async () => {
    render(<CommandPalette />)
    const input = await openPalette()
    fireEvent.change(input, { target: { value: 'ai providers' } })
    expect(await screen.findByRole('option', { name: /ai providers/i })).toBeInTheDocument()
    fireEvent.change(input, { target: { value: 'secrets' } })
    expect(await screen.findByRole('option', { name: /^secrets$/i })).toBeInTheDocument()
  })

  it('omits AI Providers and Secrets for a REVIEWER but keeps Members & Roles and Notifications', async () => {
    mockRole = 'REVIEWER'
    render(<CommandPalette />)
    const input = await openPalette()
    fireEvent.change(input, { target: { value: 'ai providers' } })
    expect(screen.queryByRole('option', { name: /ai providers/i })).not.toBeInTheDocument()
    fireEvent.change(input, { target: { value: 'secrets' } })
    expect(screen.queryByRole('option', { name: /^secrets$/i })).not.toBeInTheDocument()
    fireEvent.change(input, { target: { value: 'members' } })
    expect(await screen.findByRole('option', { name: /members & roles/i })).toBeInTheDocument()
    fireEvent.change(input, { target: { value: 'notifications' } })
    expect(await screen.findByRole('option', { name: /notifications/i })).toBeInTheDocument()
  })

  it('shows a registered extra group\'s actions (registry API)', async () => {
    const perform = vi.fn()
    const unregister = registerPaletteActions({
      group: 'Custom',
      actions: [{ id: 'custom-1', label: 'Custom Action', perform }],
    })
    try {
      render(<CommandPalette />)
      await openPalette()
      expect(await screen.findByRole('option', { name: 'Custom Action' })).toBeInTheDocument()
    } finally {
      unregister()
    }
  })
})
