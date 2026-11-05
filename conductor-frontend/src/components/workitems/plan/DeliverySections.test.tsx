import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkItemDeliveryFields } from '@/types/workItem'

const { patchDelivery, fetchMilestones, fetchDependencies, addDependency, removeDependency, apiGet, toastError } = vi.hoisted(() => ({
  patchDelivery: vi.fn(),
  fetchMilestones: vi.fn(),
  fetchDependencies: vi.fn(),
  addDependency: vi.fn(),
  removeDependency: vi.fn(),
  apiGet: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('@/lib/delivery', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/delivery')>()
  return { ...actual, patchDelivery, fetchMilestones, fetchDependencies, addDependency, removeDependency }
})
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return { ...actual, apiGet }
})
vi.mock('@/components/ui/toast', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/ui/toast')>()
  return { ...actual, toastError }
})
const PARENT_READY: WorkItemRefState = {
  status: 'ready',
  ref: { id: 'prd', displayId: 'COND-120', title: 'The PRD', href: '/app/projects/p1/engineering/prds/COND-120' },
}

import { DeliverySections } from './DeliverySections'
import type { WorkItemRefState } from './useWorkItemRef'

const MILESTONES = [
  { id: 'm1', projectId: 'p1', name: 'Beta', state: 'OPEN', sortOrder: 0, createdAt: '', updatedAt: '' },
  { id: 'm2', projectId: 'p1', name: 'GA', state: 'OPEN', sortOrder: 1, createdAt: '', updatedAt: '' },
  { id: 'm3', projectId: 'p1', name: 'Old', state: 'CLOSED', sortOrder: 2, createdAt: '', updatedAt: '' },
]

function serverError(detail: string) {
  return Object.assign(new Error(detail), { status: 400, detail })
}

function setup(delivery: WorkItemDeliveryFields = {}, canEdit = true, parent: WorkItemRefState = PARENT_READY) {
  const onChanged = vi.fn()
  render(<DeliverySections projectId="p1" issueId="wi-1" token="tok" canEdit={canEdit} delivery={delivery} parent={parent} onChanged={onChanged} />)
  return { onChanged, user: userEvent.setup() }
}

describe('DeliverySections', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    patchDelivery.mockResolvedValue({})
    fetchMilestones.mockResolvedValue(MILESTONES)
    fetchDependencies.mockResolvedValue({ blocks: [], blockedBy: [], discoveredFrom: [], discovered: [] })
    addDependency.mockResolvedValue({})
    removeDependency.mockResolvedValue(undefined)
  })

  describe('priority', () => {
    it('saves a new priority through patchDelivery', async () => {
      const { onChanged, user } = setup({ priority: 'P2' })
      await user.selectOptions(screen.getByLabelText('Priority'), 'P0')
      expect(patchDelivery).toHaveBeenCalledWith('p1', 'wi-1', { priority: 'P0' }, 'tok')
      await waitFor(() => expect(onChanged).toHaveBeenCalledWith({ priority: 'P0' }))
    })

    it('clears with "No priority" as null', async () => {
      const { user } = setup({ priority: 'P2' })
      await user.selectOptions(screen.getByLabelText('Priority'), '')
      expect(patchDelivery).toHaveBeenCalledWith('p1', 'wi-1', { priority: null }, 'tok')
    })

    it('toasts the server message and keeps the old value when the save fails', async () => {
      patchDelivery.mockRejectedValue(serverError('Priority is locked'))
      const { onChanged, user } = setup({ priority: 'P2' })
      await user.selectOptions(screen.getByLabelText('Priority'), 'P0')
      await waitFor(() => expect(toastError).toHaveBeenCalledWith('Priority is locked'))
      expect(onChanged).not.toHaveBeenCalled()
      expect(screen.getByLabelText('Priority')).toHaveValue('P2')
    })

    it('is read-only for a reviewer', () => {
      setup({ priority: 'P1' }, false)
      expect(screen.queryByLabelText('Priority')).not.toBeInTheDocument()
      expect(screen.getByText('P1 · High')).toBeInTheDocument()
    })
  })

  describe('milestone', () => {
    it('offers open milestones and saves the choice', async () => {
      const { onChanged, user } = setup({})
      const select = await screen.findByLabelText('Milestone')
      expect(screen.queryByRole('option', { name: 'Old' })).not.toBeInTheDocument()
      await user.selectOptions(select, 'm2')
      expect(patchDelivery).toHaveBeenCalledWith('p1', 'wi-1', { milestoneId: 'm2' }, 'tok')
      await waitFor(() => expect(onChanged).toHaveBeenCalledWith({ milestoneId: 'm2', milestoneName: 'GA' }))
    })

    it('keeps the current milestone selectable even when it is closed', async () => {
      setup({ milestoneId: 'm3', milestoneName: 'Old' })
      expect(await screen.findByRole('option', { name: 'Old' })).toBeInTheDocument()
    })

    it('toasts when the save fails', async () => {
      patchDelivery.mockRejectedValue(serverError('Milestone is closed'))
      const { user } = setup({})
      await user.selectOptions(await screen.findByLabelText('Milestone'), 'm1')
      await waitFor(() => expect(toastError).toHaveBeenCalledWith('Milestone is closed'))
      expect(screen.getByLabelText('Milestone')).toHaveValue('')
    })
  })

  describe('appetite', () => {
    it('saves on blur and shows the server-normalised label', async () => {
      patchDelivery.mockResolvedValue({ appetite: '2 weeks', appetiteDays: 14 })
      const { onChanged, user } = setup({})
      const input = screen.getByLabelText('Appetite')
      await user.type(input, '2w')
      await user.tab()
      expect(patchDelivery).toHaveBeenCalledWith('p1', 'wi-1', { appetite: '2w' }, 'tok')
      await waitFor(() => expect(input).toHaveValue('2 weeks'))
      expect(onChanged).toHaveBeenCalledWith({ appetite: '2 weeks', appetiteDays: 14 })
    })

    it('toasts the 400 message and reverts', async () => {
      patchDelivery.mockRejectedValue(serverError('Unrecognised appetite "soon"'))
      const { user } = setup({ appetite: '1 week' })
      const input = screen.getByLabelText('Appetite')
      await user.clear(input)
      await user.type(input, 'soon')
      await user.tab()
      await waitFor(() => expect(toastError).toHaveBeenCalledWith('Unrecognised appetite "soon"'))
      expect(input).toHaveValue('1 week')
    })

    it('does not call the server when nothing changed', async () => {
      const { user } = setup({ appetite: '1 week' })
      await user.click(screen.getByLabelText('Appetite'))
      await user.tab()
      expect(patchDelivery).not.toHaveBeenCalled()
    })
  })

  describe('parent', () => {
    it('shows the loading skeleton only while the parent is loading', () => {
      setup({ parentWorkItemId: 'prd' }, true, { status: 'loading', ref: null })
      expect(screen.getByLabelText('Loading parent')).toBeInTheDocument()
    })

    it('shows plain error text, not the skeleton, when the parent failed to load', () => {
      setup({ parentWorkItemId: 'prd' }, true, { status: 'error', ref: null })
      expect(screen.getByText('Could not load the parent.')).toBeInTheDocument()
      expect(screen.queryByLabelText('Loading parent')).not.toBeInTheDocument()
    })

    it('links the parent and counts sub-items', () => {
      setup({ parentWorkItemId: 'prd', childCount: 3 })
      expect(screen.getByRole('link', { name: /COND-120/ })).toHaveAttribute('href', '/app/projects/p1/engineering/prds/COND-120')
      expect(screen.getByText('3 sub-items')).toBeInTheDocument()
    })

    it('is absent for a plain item', () => {
      setup({})
      expect(screen.queryByText('Parent')).not.toBeInTheDocument()
    })
  })

  describe('blocked by', () => {
    const BLOCKER = { dependencyId: 'd1', workItemId: 'b1', displayId: 'COND-7', title: 'Schema first', status: 'TODO' }

    it('lists blockers and removes one', async () => {
      fetchDependencies.mockResolvedValue({ blocks: [], blockedBy: [BLOCKER], discoveredFrom: [], discovered: [] })
      const { user } = setup({})
      expect(await screen.findByText('Schema first')).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Remove blocker COND-7' }))
      expect(removeDependency).toHaveBeenCalledWith('p1', 'wi-1', 'd1', 'tok')
      await waitFor(() => expect(screen.queryByText('Schema first')).not.toBeInTheDocument())
    })

    it('adds a blocker by display id, with the blocker on the BLOCKS side', async () => {
      apiGet.mockResolvedValue({ id: 'b9' })
      const { user } = setup({})
      await screen.findByText('Nothing blocks this')
      await user.type(screen.getByLabelText('Add blocker by ID'), 'cond-9')
      await user.click(screen.getByRole('button', { name: 'Add' }))
      expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/p1/work-items/by-display/COND-9', 'tok')
      await waitFor(() => expect(addDependency).toHaveBeenCalledWith('p1', 'b9', 'BLOCKS', 'wi-1', 'tok'))
      // Re-read afterwards so the new blocker shows.
      await waitFor(() => expect(fetchDependencies).toHaveBeenCalledTimes(2))
    })

    it('says so when the display id is unknown', async () => {
      apiGet.mockRejectedValue(Object.assign(new Error('x'), { status: 404 }))
      const { user } = setup({})
      await screen.findByText('Nothing blocks this')
      await user.type(screen.getByLabelText('Add blocker by ID'), 'COND-999')
      await user.click(screen.getByRole('button', { name: 'Add' }))
      await waitFor(() => expect(toastError).toHaveBeenCalledWith('No work item COND-999 in this workspace'))
      expect(addDependency).not.toHaveBeenCalled()
    })

    it('toasts the server reason when the link is refused (a cycle)', async () => {
      apiGet.mockResolvedValue({ id: 'b9' })
      addDependency.mockRejectedValue(Object.assign(new Error('x'), { status: 409, detail: 'That would create a cycle' }))
      const { user } = setup({})
      await screen.findByText('Nothing blocks this')
      await user.type(screen.getByLabelText('Add blocker by ID'), 'COND-9')
      await user.click(screen.getByRole('button', { name: 'Add' }))
      await waitFor(() => expect(toastError).toHaveBeenCalledWith('That would create a cycle'))
    })
  })
})
