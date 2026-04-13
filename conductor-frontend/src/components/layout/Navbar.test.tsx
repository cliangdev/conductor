import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

const mockPush = vi.fn()
const mockSignOut = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: mockPush }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: { name: 'Cal L', email: 'cal@example.com', avatarUrl: null },
    signOut: mockSignOut,
  }),
}))

vi.mock('@/contexts/SidebarContext', () => ({
  useSidebar: () => ({ toggleSidebar: vi.fn() }),
}))

vi.mock('@/components/ui/ThemeToggle', () => ({
  ThemeToggle: () => null,
}))

vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuItem: ({ children, onSelect, className }: { children: React.ReactNode; onSelect?: () => void; className?: string }) => (
    <div role="menuitem" className={className} onClick={onSelect}>{children}</div>
  ),
  DropdownMenuSeparator: () => <hr />,
}))

import { Navbar } from './Navbar'

describe('Navbar user dropdown', () => {
  it('does not show an API Keys link in the dropdown', () => {
    render(<Navbar />)
    expect(screen.queryByRole('link', { name: /api keys/i })).not.toBeInTheDocument()
  })

  it('shows the Sign out option', () => {
    render(<Navbar />)
    expect(screen.getByText(/sign out/i)).toBeInTheDocument()
  })

  it('calls signOut and redirects to /login when Sign out is clicked', async () => {
    mockSignOut.mockResolvedValue(undefined)
    render(<Navbar />)
    fireEvent.click(screen.getByText(/sign out/i))
    await vi.waitFor(() => {
      expect(mockSignOut).toHaveBeenCalled()
      expect(mockPush).toHaveBeenCalledWith('/login')
    })
  })
})
