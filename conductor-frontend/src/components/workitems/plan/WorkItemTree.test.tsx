import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkItemCriterion, WorkItemTreeNode } from '@/types/workItem'

const { fetchTree, fetchCriteria, patchCriterion, toastError } = vi.hoisted(() => ({
  fetchTree: vi.fn(),
  fetchCriteria: vi.fn(),
  patchCriterion: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('@/lib/delivery', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/delivery')>()
  return { ...actual, fetchTree, fetchCriteria, patchCriterion }
})
vi.mock('@/components/ui/toast', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/ui/toast')>()
  return { ...actual, toastError }
})
vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return {
    ...actual,
    useWorkflowViews: () => ({
      TASK: { slug: 'TASK', noun: 'Task', area: 'ENGINEERING', version: 1, types: ['TASK'], statuses: [] },
    }),
  }
})

import { WorkItemTree } from './WorkItemTree'

const signals = (over: Partial<NonNullable<WorkItemTreeNode['signals']>> = {}) => ({
  blocked: false,
  blockedBy: [],
  ready: false,
  stale: false,
  needsInput: false,
  ...over,
})

function node(over: Partial<WorkItemTreeNode> & Pick<WorkItemTreeNode, 'id' | 'displayId' | 'title'>): WorkItemTreeNode {
  return {
    type: 'TASK',
    workflow: 'TASK',
    status: 'TODO',
    childCount: 0,
    criteriaTotal: 0,
    criteriaMet: 0,
    children: [],
    ...over,
  }
}

const TREE: WorkItemTreeNode = node({
  id: 'prd',
  displayId: 'COND-1',
  title: 'The PRD',
  type: 'PRD',
  workflow: 'ENGINEERING',
  childCount: 2,
  children: [
    node({
      id: 'epic',
      displayId: 'COND-2',
      title: 'Backend epic',
      priority: 'P1',
      childCount: 1,
      children: [
        node({
          id: 'leaf',
          displayId: 'COND-3',
          title: 'Add the endpoint',
          criteriaTotal: 3,
          criteriaMet: 1,
          signals: signals({ blocked: true, blockedBy: [{ id: 'x', displayId: 'COND-9', title: 'Other' }] }),
          agentActivity: { agentLabel: 'coder', mode: 'ASSIGNEE', activity: 'WORKING', updatedAt: '2026-11-01T00:00:00Z' },
        }),
      ],
    }),
    node({ id: 'ask', displayId: 'COND-4', title: 'Needs a call', signals: signals({ needsInput: true, stale: true }) }),
  ],
})

const CRITERIA: WorkItemCriterion[] = [
  { criterionId: 'c1', position: 0, text: 'Returns 200', kind: 'auto', met: true, evidence: 'ApiTest passed' },
  { criterionId: 'c2', position: 1, text: 'Reviewed by a human', kind: 'manual', met: false },
  { criterionId: 'c3', position: 2, text: 'Docs updated', kind: 'manual', met: false },
]

function renderTree(canEdit = true) {
  return render(<WorkItemTree projectId="p1" rootId="prd" token="tok" canEdit={canEdit} />)
}

describe('WorkItemTree', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    fetchTree.mockResolvedValue(TREE)
    fetchCriteria.mockResolvedValue(CRITERIA)
    patchCriterion.mockResolvedValue(undefined)
  })

  it('shows a skeleton, then nested rows without the root itself', async () => {
    renderTree()
    expect(screen.getByLabelText('Loading plan')).toBeInTheDocument()
    expect(await screen.findByTestId('plan-row-COND-2')).toBeInTheDocument()
    expect(screen.getByTestId('plan-row-COND-3')).toBeInTheDocument()
    expect(screen.getByTestId('plan-row-COND-4')).toBeInTheDocument()
    expect(screen.queryByTestId('plan-row-COND-1')).not.toBeInTheDocument()
    // Indented one step per depth.
    const epicPad = parseInt(screen.getByTestId('plan-row-COND-2').style.paddingLeft, 10)
    const leafPad = parseInt(screen.getByTestId('plan-row-COND-3').style.paddingLeft, 10)
    expect(leafPad).toBeGreaterThan(epicPad)
  })

  it('links a title to the child detail page', async () => {
    renderTree()
    const link = await screen.findByRole('link', { name: 'Add the endpoint' })
    expect(link).toHaveAttribute('href', '/app/projects/p1/engineering/tasks/COND-3')
  })

  it('shows priority, signals, criteria count and the working-agent dot', async () => {
    renderTree()
    const epic = await screen.findByTestId('plan-row-COND-2')
    expect(within(epic).getByText('P1 · High')).toBeInTheDocument()

    const leaf = screen.getByTestId('plan-row-COND-3')
    expect(within(leaf).getByText('Blocked')).toBeInTheDocument()
    expect(within(leaf).getByText('1/3')).toBeInTheDocument()
    expect(within(leaf).getByRole('img', { name: 'Agent working' })).toBeInTheDocument()

    const ask = screen.getByTestId('plan-row-COND-4')
    expect(within(ask).getByText('Needs input')).toBeInTheDocument()
    expect(within(ask).getByText('Stale')).toBeInTheDocument()
  })

  it('collapses a parent to hide its children', async () => {
    const user = userEvent.setup()
    renderTree()
    await screen.findByTestId('plan-row-COND-3')
    await user.click(screen.getByRole('button', { name: 'Collapse COND-2' }))
    expect(screen.queryByTestId('plan-row-COND-3')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Expand COND-2' }))
    expect(screen.getByTestId('plan-row-COND-3')).toBeInTheDocument()
  })

  it('expands a leaf to its criteria; auto ones are read-only with their evidence', async () => {
    const user = userEvent.setup()
    renderTree()
    await user.click(await screen.findByRole('button', { name: 'Expand COND-3' }))
    expect(await screen.findByText('Returns 200')).toBeInTheDocument()
    expect(screen.getByText('ApiTest passed')).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox')).toHaveLength(2) // only the manual ones
  })

  it('ticks a manual criterion optimistically through patchCriterion', async () => {
    const user = userEvent.setup()
    renderTree()
    await user.click(await screen.findByRole('button', { name: 'Expand COND-3' }))
    await user.click(await screen.findByLabelText('Reviewed by a human'))

    expect(patchCriterion).toHaveBeenCalledWith('p1', 'leaf', 'c2', { met: true }, 'tok')
    expect(screen.getByLabelText('Reviewed by a human')).toBeChecked()
    expect(within(screen.getByTestId('plan-row-COND-3')).getByText('2/3')).toBeInTheDocument()
    expect(toastError).not.toHaveBeenCalled()
  })

  it('reverts and toasts when the toggle fails', async () => {
    patchCriterion.mockRejectedValue(Object.assign(new Error('nope'), { detail: 'Not allowed' }))
    const user = userEvent.setup()
    renderTree()
    await user.click(await screen.findByRole('button', { name: 'Expand COND-3' }))
    await user.click(await screen.findByLabelText('Reviewed by a human'))

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Not allowed'))
    expect(screen.getByLabelText('Reviewed by a human')).not.toBeChecked()
    expect(within(screen.getByTestId('plan-row-COND-3')).getByText('1/3')).toBeInTheDocument()
  })

  it('does not let a reviewer tick manual criteria', async () => {
    const user = userEvent.setup()
    renderTree(false)
    await user.click(await screen.findByRole('button', { name: 'Expand COND-3' }))
    expect(await screen.findByLabelText('Reviewed by a human')).toBeDisabled()
  })

  it('points at /conductor:breakdown when the PRD has no children', async () => {
    fetchTree.mockResolvedValue({ ...TREE, childCount: 0, children: [] })
    renderTree()
    expect(await screen.findByText('No plan yet — run /conductor:breakdown')).toBeInTheDocument()
  })

  it('offers a retry when the plan fails to load', async () => {
    fetchTree.mockRejectedValueOnce(new Error('boom'))
    const user = userEvent.setup()
    renderTree()
    await user.click(await screen.findByRole('button', { name: 'Retry' }))
    expect(await screen.findByTestId('plan-row-COND-2')).toBeInTheDocument()
  })
})
