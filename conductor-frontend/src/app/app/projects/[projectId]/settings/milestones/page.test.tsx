import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const apiGet = vi.fn()
const apiPost = vi.fn()
const apiPatch = vi.fn()

vi.mock('next/navigation', () => ({ useParams: () => ({ projectId: 'proj-1' }) }))
vi.mock('@/contexts/AuthContext', () => ({ useAuth: () => ({ accessToken: 'token' }) }))
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return {
    ...actual,
    apiGet: (...a: unknown[]) => apiGet(...a),
    apiPost: (...a: unknown[]) => apiPost(...a),
    apiPatch: (...a: unknown[]) => apiPatch(...a),
  }
})

import MilestonesSettingsPage from './page'

const beta = { id: 'm1', projectId: 'proj-1', name: 'Beta', targetOn: '2026-12-01', state: 'OPEN', sortOrder: 0, createdAt: '', updatedAt: '' }

beforeEach(() => {
  apiGet.mockReset().mockResolvedValue([beta])
  apiPost.mockReset().mockResolvedValue({ ...beta, id: 'm2', name: 'GA' })
  apiPatch.mockReset().mockResolvedValue(undefined)
})

describe('milestone settings page', () => {
  it('lists milestones and creates one with a POST body', async () => {
    const user = userEvent.setup()
    render(<MilestonesSettingsPage />)
    expect(await screen.findByText('Beta')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Add milestone' }))
    await user.type(screen.getByLabelText('Name'), 'GA')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    expect(apiPost.mock.calls[0][0]).toBe('/api/v2/projects/proj-1/milestones')
    expect(apiPost.mock.calls[0][1]).toEqual({ name: 'GA', targetOn: null })
  })

  it('closes a milestone with a state patch', async () => {
    const user = userEvent.setup()
    render(<MilestonesSettingsPage />)
    await screen.findByText('Beta')
    await user.click(screen.getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(apiPatch).toHaveBeenCalled())
    expect(apiPatch.mock.calls[0][0]).toBe('/api/v2/projects/proj-1/milestones/m1')
    expect(apiPatch.mock.calls[0][1]).toEqual({ state: 'CLOSED' })
  })

  it('shows the empty state with no milestones', async () => {
    apiGet.mockResolvedValue([])
    render(<MilestonesSettingsPage />)
    expect(await screen.findByText('No milestones yet')).toBeInTheDocument()
  })
})
