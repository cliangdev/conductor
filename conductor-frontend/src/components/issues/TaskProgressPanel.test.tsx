import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

vi.mock('@/lib/api', () => ({ apiGet: vi.fn() }))
vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

import { TaskProgressPanel } from './TaskProgressPanel'
import { apiGet } from '@/lib/api'

const mockApiGet = vi.mocked(apiGet)

const sampleTasksData = {
  epics: [
    {
      id: 'epic-1',
      title: 'Epic One',
      tasks: [
        { id: 't-1', title: 'Task A', status: 'COMPLETED' },
        { id: 't-2', title: 'Task B', status: 'PENDING' },
        { id: 't-3', title: 'Task C', status: 'BLOCKED' },
      ],
    },
    {
      id: 'epic-2',
      title: 'Epic Two',
      tasks: [
        { id: 't-4', title: 'Task D', status: 'COMPLETED' },
        { id: 't-5', title: 'Task E', status: 'COMPLETED' },
      ],
    },
  ],
}

describe('TaskProgressPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders nothing when API returns 404', async () => {
    const err = Object.assign(new Error('API error: 404'), { status: 404 })
    mockApiGet.mockRejectedValueOnce(err)

    const { container } = render(
      <TaskProgressPanel issueId="issue-1" projectId="proj-1" />
    )

    await waitFor(() => {
      expect(container.firstChild).toBeNull()
    })
  })

  it('renders nothing when API returns empty epics', async () => {
    mockApiGet.mockResolvedValueOnce({ epics: [] })

    const { container } = render(
      <TaskProgressPanel issueId="issue-1" projectId="proj-1" />
    )

    await waitFor(() => {
      expect(container.firstChild).toBeNull()
    })
  })

  it('renders progress bar with correct percentage', async () => {
    // 3 completed out of 5 total = 60%
    mockApiGet.mockResolvedValueOnce(sampleTasksData)

    render(<TaskProgressPanel issueId="issue-1" projectId="proj-1" />)

    await waitFor(() => {
      expect(screen.getByText('3 / 5 tasks complete')).toBeInTheDocument()
    })

    const progressBar = screen.getByRole('progressbar')
    expect(progressBar).toBeInTheDocument()
    expect(progressBar).toHaveAttribute('aria-valuenow', '60')
  })

  it('calls apiGet with correct path', async () => {
    mockApiGet.mockResolvedValueOnce(sampleTasksData)

    render(<TaskProgressPanel issueId="issue-42" projectId="proj-99" />)

    await waitFor(() => {
      expect(mockApiGet).toHaveBeenCalledWith(
        '/api/v1/projects/proj-99/issues/issue-42/tasks',
        'test-token'
      )
    })
  })

  it('renders epic titles when expanded', async () => {
    mockApiGet.mockResolvedValueOnce(sampleTasksData)

    render(<TaskProgressPanel issueId="issue-1" projectId="proj-1" />)

    await waitFor(() => {
      expect(screen.getByText('Epic One')).toBeInTheDocument()
      expect(screen.getByText('Epic Two')).toBeInTheDocument()
    })
  })

  it('shows task titles', async () => {
    mockApiGet.mockResolvedValueOnce(sampleTasksData)

    render(<TaskProgressPanel issueId="issue-1" projectId="proj-1" />)

    await waitFor(() => {
      expect(screen.getByText('Task A')).toBeInTheDocument()
      expect(screen.getByText('Task B')).toBeInTheDocument()
    })
  })
})
