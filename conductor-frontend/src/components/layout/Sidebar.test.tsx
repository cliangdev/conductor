import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  usePathname: () => '/app/projects/proj-1/issues',
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
  it('renders Setup link when a project is active', () => {
    render(<Sidebar />)
    const setupLink = screen.getByRole('link', { name: /setup/i })
    expect(setupLink).toBeInTheDocument()
    expect(setupLink).toHaveAttribute('href', '/app/projects/proj-1/setup')
  })

  it('renders Setup link below Members link', () => {
    render(<Sidebar />)
    const links = screen.getAllByRole('link')
    const linkTexts = links.map((l) => l.textContent?.trim())
    const membersIndex = linkTexts.findIndex((t) => t === 'Members')
    const setupIndex = linkTexts.findIndex((t) => t === 'Setup')
    expect(membersIndex).toBeGreaterThanOrEqual(0)
    expect(setupIndex).toBeGreaterThan(membersIndex)
  })
})
