import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

vi.mock('@/lib/api', () => ({ apiGet: vi.fn() }))
vi.mock('@/contexts/AuthContext', () => ({ useAuth: () => ({ accessToken: 'tok' }) }))

import { apiGet } from '@/lib/api'
import { OpenChangesCard } from './OpenChangesCard'

const item = (over: Record<string, unknown>) => ({
  id: 'id-1',
  displayId: 'MKT-1',
  title: 'Shorter hook',
  status: 'PLANNED',
  dueOn: '2999-01-15',
  ...over,
})

beforeEach(() => vi.clearAllMocks())

describe('OpenChangesCard', () => {
  it('renders nothing when there are no open changes', async () => {
    ;(apiGet as Mock).mockResolvedValue([item({ status: 'REVIEWED' }), item({ id: 'x', status: 'ABANDONED' })])
    const { container } = render(<OpenChangesCard projectId="proj-1" />)
    await waitFor(() => expect(apiGet).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing on error or an unexpected payload', async () => {
    ;(apiGet as Mock).mockRejectedValue(new Error('boom'))
    const { container } = render(<OpenChangesCard projectId="proj-1" />)
    await waitFor(() => expect(apiGet).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })

  it('asks for the Changes workflow sorted by due date', async () => {
    ;(apiGet as Mock).mockResolvedValue([])
    render(<OpenChangesCard projectId="proj-1" />)
    await waitFor(() => expect(apiGet).toHaveBeenCalled())
    expect((apiGet as Mock).mock.calls[0][0]).toBe(
      '/api/v2/projects/proj-1/work-items?workflow=MARKETING_CHANGES&sort=dueOn',
    )
  })

  it('lists open changes with links, review date and an overdue flag', async () => {
    ;(apiGet as Mock).mockResolvedValue([
      item({ id: 'a', displayId: 'MKT-1', title: 'Late one', status: 'LIVE', dueOn: '2000-01-02' }),
      item({ id: 'b', displayId: 'MKT-2', title: 'Future one', dueOn: '2999-01-15' }),
      item({ id: 'c', displayId: 'MKT-3', title: 'Done one', status: 'REVIEWED' }),
    ])
    render(<OpenChangesCard projectId="proj-1" />)
    expect(await screen.findByRole('region', { name: 'Open changes' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Late one' })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/marketing/changes/MKT-1',
    )
    expect(screen.getByRole('link', { name: 'Future one' })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/marketing/changes/MKT-2',
    )
    expect(screen.queryByText('Done one')).not.toBeInTheDocument()
    expect(screen.getByText('Review on Jan 2 · overdue')).toBeInTheDocument()
    expect(screen.getByText('Review on Jan 15')).toBeInTheDocument()
    expect(screen.getByText('Live')).toBeInTheDocument()
    expect(screen.getByText('Planned')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'See all' })).toHaveAttribute('href', '/app/projects/proj-1/marketing/changes')
  })
})
