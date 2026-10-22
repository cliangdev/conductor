import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/types/workItem'
import { WorkItemFieldsCard, formatDay, optionLabel } from './WorkItemFieldsCard'

const { toastErrorSpy } = vi.hoisted(() => ({ toastErrorSpy: vi.fn() }))
vi.mock('@/components/ui/toast', async () => {
  const actual = await vi.importActual<typeof import('@/components/ui/toast')>('@/components/ui/toast')
  return { ...actual, toastError: toastErrorSpy }
})

const API = 'https://api.test'
let patchBodies: unknown[] = []
let patchRejection: { status: number; detail: string } | null = null
let patchResponse: Record<string, unknown> = {}

const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
  patchBodies.push(JSON.parse(String(init?.body ?? '{}')))
  if (patchRejection)
    return {
      ok: false, status: patchRejection.status,
      headers: { get: () => 'application/json' },
      json: async () => ({ detail: patchRejection!.detail }),
    }
  return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => patchResponse }
})

const CHANGES: WorkflowView = {
  slug: 'MARKETING_CHANGES', noun: 'Change', area: 'MARKETING_CHANGES', defaultView: 'list', version: 1,
  types: ['ADS'], dueLabel: 'Review on',
  statuses: [
    { id: 'PLANNED', label: 'Planned', category: 'open', initial: true },
    { id: 'LIVE', label: 'Live', category: 'in_progress' },
    { id: 'REVIEWED', label: 'Reviewed', category: 'terminal', terminal: true },
  ],
  transitions: [],
  fields: [
    { key: 'hypothesis', label: 'Hypothesis', type: 'longtext' },
    { key: 'liveSince', label: 'Live since', type: 'date', requiredIn: ['LIVE', 'REVIEWED'] },
    { key: 'verdict', label: 'Verdict', type: 'enum', options: ['worked', 'did_not_work'], requiredIn: ['REVIEWED'] },
    { key: 'budget', label: 'Budget', type: 'number' },
    { key: 'evidence', label: 'Evidence', type: 'url' },
  ],
}

beforeEach(() => {
  patchBodies = []
  patchRejection = null
  patchResponse = {}
  toastErrorSpy.mockClear()
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})

function renderCard(props: Partial<React.ComponentProps<typeof WorkItemFieldsCard>> = {}) {
  const onSaved = vi.fn()
  render(
    <WorkItemFieldsCard
      projectId="proj-1"
      workItemId="wi-1"
      token="tok"
      workflowView={CHANGES}
      status="LIVE"
      fields={{ hypothesis: 'Shorter titles lift clicks', liveSince: '2026-10-01', verdict: 'did_not_work' }}
      dueOn="2026-11-05"
      canEdit
      onSaved={onSaved}
      {...props}
    />
  )
  return { onSaved }
}

describe('formatting helpers', () => {
  it('words an option key and a day without shifting zones', () => {
    expect(optionLabel('did_not_work')).toBe('Did not work')
    expect(formatDay('2026-11-05')).toContain('2026')
    expect(formatDay('2026-11-05')).toContain('5')
    expect(formatDay('nonsense')).toBe('nonsense')
  })
})

describe('WorkItemFieldsCard', () => {
  it('shows each declared field by type, and the due date under the Workflow\'s own label', () => {
    renderCard()
    expect(screen.getByText('Review on')).toBeInTheDocument()
    expect(screen.getByText('Shorter titles lift clicks')).toBeInTheDocument()
    expect(screen.getByText('Did not work')).toBeInTheDocument()
    // An empty field says so rather than leaving a blank.
    expect(screen.getAllByText('Not set').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('Needed for Live, Reviewed')).toBeInTheDocument()
  })

  it('flags an item past its due date until it reaches a terminal status', () => {
    const { rerender } = render(
      <WorkItemFieldsCard
        projectId="proj-1" workItemId="wi-1" token="tok" workflowView={CHANGES}
        status="LIVE" dueOn="2020-01-01" canEdit onSaved={vi.fn()}
      />
    )
    expect(screen.getByText('Overdue')).toBeInTheDocument()
    rerender(
      <WorkItemFieldsCard
        projectId="proj-1" workItemId="wi-1" token="tok" workflowView={CHANGES}
        status="REVIEWED" dueOn="2020-01-01" canEdit onSaved={vi.fn()}
      />
    )
    expect(screen.queryByText('Overdue')).not.toBeInTheDocument()
  })

  it('saves one field with a partial PATCH and reports the server\'s fields', async () => {
    patchResponse = { fields: { hypothesis: 'New idea', liveSince: '2026-10-01' } }
    const { onSaved } = renderCard()
    await userEvent.click(screen.getByRole('button', { name: 'Edit Hypothesis' }))
    const box = screen.getByRole('textbox', { name: /hypothesis/i })
    await userEvent.clear(box)
    await userEvent.type(box, 'New idea')
    await userEvent.click(screen.getByRole('button', { name: 'Save Hypothesis' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(patchBodies).toEqual([{ fields: { hypothesis: 'New idea' } }])
    expect(onSaved).toHaveBeenCalledWith({ fields: { hypothesis: 'New idea', liveSince: '2026-10-01' } })
  })

  it('sends a number as a number and an emptied field as null', async () => {
    patchResponse = { fields: {} }
    renderCard()
    await userEvent.click(screen.getByRole('button', { name: 'Edit Budget' }))
    await userEvent.type(screen.getByLabelText('Budget'), '12.5')
    await userEvent.click(screen.getByRole('button', { name: 'Save Budget' }))
    await waitFor(() => expect(patchBodies).toHaveLength(1))
    expect(patchBodies[0]).toEqual({ fields: { budget: 12.5 } })

    await userEvent.click(screen.getByRole('button', { name: 'Edit Hypothesis' }))
    await userEvent.clear(screen.getByRole('textbox', { name: /hypothesis/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Save Hypothesis' }))
    await waitFor(() => expect(patchBodies).toHaveLength(2))
    expect(patchBodies[1]).toEqual({ fields: { hypothesis: null } })
  })

  it('edits an enum through its options', async () => {
    patchResponse = { fields: { verdict: 'worked' } }
    renderCard()
    await userEvent.click(screen.getByRole('button', { name: 'Edit Verdict' }))
    await userEvent.selectOptions(screen.getByLabelText('Verdict'), 'worked')
    await userEvent.click(screen.getByRole('button', { name: 'Save Verdict' }))
    await waitFor(() => expect(patchBodies).toEqual([{ fields: { verdict: 'worked' } }]))
  })

  it('sets and clears the due date', async () => {
    patchResponse = { dueOn: '2026-12-01' }
    const { onSaved } = renderCard()
    await userEvent.click(screen.getByRole('button', { name: 'Edit Review on' }))
    const input = screen.getByLabelText('Review on')
    await userEvent.clear(input)
    await userEvent.type(input, '2026-12-01')
    await userEvent.click(screen.getByRole('button', { name: 'Save Review on' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith({ dueOn: '2026-12-01' }))
    expect(patchBodies[0]).toEqual({ dueOn: '2026-12-01' })

    patchResponse = { dueOn: null }
    await userEvent.click(screen.getByRole('button', { name: 'Edit Review on' }))
    await userEvent.clear(screen.getByLabelText('Review on'))
    await userEvent.click(screen.getByRole('button', { name: 'Save Review on' }))
    await waitFor(() => expect(patchBodies).toHaveLength(2))
    expect(patchBodies[1]).toEqual({ dueOn: '' })
  })

  it('surfaces the server\'s message when a value is refused and keeps the editor open', async () => {
    patchRejection = { status: 400, detail: "Invalid fields: 'evidence' must be an http(s) URL" }
    const { onSaved } = renderCard()
    await userEvent.click(screen.getByRole('button', { name: 'Edit Evidence' }))
    await userEvent.type(screen.getByLabelText('Evidence'), 'nope')
    await userEvent.click(screen.getByRole('button', { name: 'Save Evidence' }))
    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalledWith("Invalid fields: 'evidence' must be an http(s) URL"))
    expect(onSaved).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Save Evidence' })).toBeInTheDocument()
  })

  it('marks the fields a refused move reported missing', () => {
    renderCard({ missing: ['verdict'], fields: {} })
    expect(screen.getByText(/Fill in Verdict before moving this change on/i)).toBeInTheDocument()
    expect(screen.getByText('Needed to continue')).toBeInTheDocument()
  })

  it('offers no edit controls to a reviewer', () => {
    renderCard({ canEdit: false })
    expect(screen.queryByRole('button', { name: /^Edit / })).not.toBeInTheDocument()
  })
})
