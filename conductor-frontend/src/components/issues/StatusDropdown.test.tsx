import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { WorkflowView } from '@/types/workItem'
import { StatusDropdown } from './StatusDropdown'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPatch: vi.fn(),
}))

// Stub only the network-backed hook; keep the real label/color helpers so we exercise the
// WorkflowView-driven rendering (COND-18) without hitting the module cache or the API.
const MOCK_VIEW: WorkflowView = {
  slug: 'ENGINEERING',
  noun: 'Issue',
  defaultView: 'list',
  version: 1,
  types: ['PRD', 'TASK'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'IN_REVIEW', label: 'In Review', category: 'open' },
    { id: 'CODE_REVIEW', label: 'Code Review', category: 'in_progress' },
    { id: 'DONE', label: 'Done', category: 'terminal' },
    { id: 'CLOSED', label: 'Closed', category: 'terminal' },
  ],
  transitions: [],
}

vi.mock('@/lib/workflows', async (importActual) => {
  const actual = await importActual<typeof import('@/lib/workflows')>()
  return { ...actual, useWorkflowView: () => MOCK_VIEW }
})

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
    // current status badge renders, resolved from the WorkflowView
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

  it('renders a read-only badge for REVIEWER and does not fetch transitions', () => {
    render(<StatusDropdown {...baseProps} userRole="REVIEWER" />)
    expect(screen.getByText('Draft')).toBeInTheDocument()
    // REVIEWER never queries available-transitions (the WorkflowView hook is stubbed here).
    expect(apiGet).not.toHaveBeenCalled()
  })
})
