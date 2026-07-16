import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

const mockToggleSidebar = vi.fn()

vi.mock('next/navigation', () => ({
  usePathname: () => '/app/projects/proj-1/engineering/issues',
}))

vi.mock('@/contexts/SidebarContext', () => ({
  useSidebar: () => ({ toggleSidebar: mockToggleSidebar }),
}))

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => ({
    projects: [{ id: 'proj-1', name: 'Test Workspace' }],
    activeProject: { id: 'proj-1', name: 'Test Workspace' },
  }),
}))

import { Navbar } from './Navbar'

describe('Navbar (mobile bar)', () => {
  it('renders the hamburger button and toggles the sidebar', () => {
    render(<Navbar />)
    fireEvent.click(screen.getByRole('button', { name: /open menu/i }))
    expect(mockToggleSidebar).toHaveBeenCalled()
  })

  it('shows the current workspace name as the mobile title', () => {
    render(<Navbar />)
    expect(screen.getByText('Test Workspace')).toBeInTheDocument()
  })

  it('does not render a user menu (owned by the Sidebar footer)', () => {
    render(<Navbar />)
    expect(screen.queryByText(/sign out/i)).not.toBeInTheDocument()
  })
})
