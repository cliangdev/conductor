import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

let pathname = '/app/projects/proj-1/settings/general'
vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  usePathname: () => pathname,
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

import SettingsLayout from './layout'

describe('SettingsLayout rail', () => {
  beforeEach(() => {
    pathname = '/app/projects/proj-1/settings/general'
    mockRole = 'ADMIN'
  })

  it('renders all seven sections for an admin', () => {
    render(<SettingsLayout>content</SettingsLayout>)
    expect(screen.getByRole('link', { name: /^general$/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /members & roles/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^api keys$/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ai providers/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^secrets$/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^notifications$/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^cli$/i })).toBeInTheDocument()
  })

  it('hides the credential/provisioning sections (AI Providers, Secrets) for a REVIEWER', () => {
    mockRole = 'REVIEWER'
    render(<SettingsLayout>content</SettingsLayout>)
    expect(screen.queryByRole('link', { name: /ai providers/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /^secrets$/i })).not.toBeInTheDocument()
  })

  it('keeps General, Members & Roles, API Keys, Notifications, and CLI visible for a REVIEWER', () => {
    mockRole = 'REVIEWER'
    render(<SettingsLayout>content</SettingsLayout>)
    expect(screen.getByRole('link', { name: /^general$/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /members & roles/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^api keys$/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^notifications$/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /^cli$/i })).toBeInTheDocument()
  })

  it('marks the active section via aria-current', () => {
    pathname = '/app/projects/proj-1/settings/members'
    render(<SettingsLayout>content</SettingsLayout>)
    expect(screen.getByRole('link', { name: /members & roles/i })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: /^general$/i })).not.toHaveAttribute('aria-current')
  })

  it('renders the page content in the right panel', () => {
    render(<SettingsLayout><span>leaf page content</span></SettingsLayout>)
    expect(screen.getByText('leaf page content')).toBeInTheDocument()
  })
})
