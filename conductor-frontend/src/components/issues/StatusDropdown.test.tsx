import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { StatusDropdown } from './StatusDropdown'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPatch: vi.fn(),
}))

import { apiGet, apiPatch } from '@/lib/api'

const baseProps = {
  projectId: 'proj-1',
  issueId: 'issue-1',
  currentStatus: 'DRAFT',
  token: 'tok',
  onStatusChanged: vi.fn(),
}

describe('StatusDropdown (COND-18 available-transitions)', () => {
  beforeEach(() => vi.clearAllMocks())

  it('fetches available transitions and offers them', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue({
      workflow: 'ENGINEERING',
      currentStatus: 'DRAFT',
      transitions: [{ toStatus: 'IN_REVIEW', label: 'Submit for review' }],
    })

    render(<StatusDropdown {...baseProps} userRole="CREATOR" />)

    await waitFor(() =>
      expect(apiGet).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/issues/issue-1/available-transitions',
        'tok'
      )
    )
    // current status badge renders
    expect(screen.getByText('Draft')).toBeInTheDocument()
  })

  it('does not offer gated transitions the backend withholds', async () => {
    // The backend omits a review-gated edge until satisfied, so the dropdown simply won't list it.
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue({
      workflow: 'ENGINEERING',
      currentStatus: 'CODE_REVIEW',
      transitions: [{ toStatus: 'CLOSED', label: 'Close' }],
    })

    render(<StatusDropdown {...baseProps} currentStatus="CODE_REVIEW" userRole="CREATOR" />)

    await waitFor(() => expect(apiGet).toHaveBeenCalled())
    expect(screen.queryByText('Done')).not.toBeInTheDocument()
    expect(apiPatch).not.toHaveBeenCalled()
  })

  it('renders a read-only badge for REVIEWER and does not fetch', () => {
    render(<StatusDropdown {...baseProps} userRole="REVIEWER" />)
    expect(screen.getByText('Draft')).toBeInTheDocument()
    expect(apiGet).not.toHaveBeenCalled()
  })
})
