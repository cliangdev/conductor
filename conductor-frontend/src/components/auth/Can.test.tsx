import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { Can } from './Can'

const mockUsePermissions = vi.fn()
vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => mockUsePermissions(),
}))

describe('Can', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders nothing while the role is loading', () => {
    mockUsePermissions.mockReturnValue({ loading: true, can: () => true })
    render(<Can do="workflow.manage">protected</Can>)
    expect(screen.queryByText('protected')).not.toBeInTheDocument()
  })

  it('renders children when permitted', () => {
    mockUsePermissions.mockReturnValue({ loading: false, can: () => true })
    render(<Can do="workflow.manage">protected</Can>)
    expect(screen.getByText('protected')).toBeInTheDocument()
  })

  it('renders the fallback when denied', () => {
    mockUsePermissions.mockReturnValue({ loading: false, can: () => false })
    render(
      <Can do="workflow.manage" fallback={<span>read-only</span>}>
        protected
      </Can>,
    )
    expect(screen.getByText('read-only')).toBeInTheDocument()
    expect(screen.queryByText('protected')).not.toBeInTheDocument()
  })
})
