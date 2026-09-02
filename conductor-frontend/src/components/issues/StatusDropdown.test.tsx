import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import type { WorkflowView } from '@/types/workItem'
import { TikTokPublishGateProvider } from '@/components/marketing/TikTokConsentStep'
import { StatusDropdown } from './StatusDropdown'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPatch: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

vi.mock('@/components/ui/toast', () => ({ toastError: vi.fn() }))

// Flatten the portal-based Radix dropdown so items are visible in jsdom (same approach as
// RuntimeTargetsPanel.test.tsx). The item stub mirrors Radix: `disabled` only marks the element
// (`aria-disabled`/`data-disabled`) — Radix blocks it with CSS `pointer-events`, which jsdom does
// not enforce — so a click still reaches the handler and exercises the in-handler guard too.
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuItem: ({
    children,
    onClick,
    disabled,
    title,
  }: {
    children: React.ReactNode
    onClick?: () => void
    disabled?: boolean
    title?: string
  }) => (
    <div
      role="menuitem"
      onClick={onClick}
      title={title}
      aria-disabled={disabled || undefined}
      data-disabled={disabled ? '' : undefined}
    >
      {children}
    </div>
  ),
}))

// Stub only the network-backed hook; keep the real label/color helpers so we exercise the
// WorkflowView-driven rendering (COND-18) without hitting the module cache or the API.
const ENGINEERING_VIEW: WorkflowView = {
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

// A Post lifecycle: DRAFT → IN_REVIEW is the submit-for-approval move, because IN_REVIEW is the
// status with the outgoing review-gated edge. DRAFT → ARCHIVED carries no review gate.
const MARKETING_VIEW: WorkflowView = {
  slug: 'MARKETING',
  noun: 'Post',
  defaultView: 'list',
  version: 1,
  types: ['POST'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'IN_REVIEW', label: 'In Review', category: 'open' },
    { id: 'SCHEDULED', label: 'Scheduled', category: 'in_progress' },
    { id: 'ARCHIVED', label: 'Archived', category: 'terminal' },
  ],
  transitions: [
    { from: 'DRAFT', to: 'IN_REVIEW', label: 'Submit for approval' },
    { from: 'DRAFT', to: 'ARCHIVED', label: 'Archive' },
    { from: 'IN_REVIEW', to: 'SCHEDULED', label: 'Approve', requiresReview: true },
  ],
}

let mockView: WorkflowView = ENGINEERING_VIEW

vi.mock('@/lib/workflows', async (importActual) => {
  const actual = await importActual<typeof import('@/lib/workflows')>()
  return { ...actual, useWorkflowView: () => mockView }
})

import { apiGet, apiPatch } from '@/lib/api'
import { toastError } from '@/components/ui/toast'

const baseProps = {
  projectId: 'proj-1',
  issueId: 'issue-1',
  currentStatus: 'DRAFT',
  token: 'tok',
  onStatusChanged: vi.fn(),
}

const GATE_REASON = 'Review the preview and the destination account, then consent.'

const POST_TRANSITIONS = [
  { toStatus: 'IN_REVIEW', label: 'Submit for approval' },
  { toStatus: 'ARCHIVED', label: 'Archive' },
]

function itemFor(label: string): HTMLElement {
  return screen.getByText(label).closest('[role="menuitem"]') as HTMLElement
}

describe('StatusDropdown (COND-18 available-transitions)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockView = ENGINEERING_VIEW
  })

  it('fetches available transitions and offers them', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue({
      workflow: 'ENGINEERING',
      currentStatus: 'DRAFT',
      transitions: [{ toStatus: 'IN_REVIEW', label: 'Submit for review' }],
    })

    render(<StatusDropdown {...baseProps} userRole="CREATOR" />)

    await waitFor(() =>
      expect(apiGet).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/work-items/issue-1/available-transitions',
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

describe('StatusDropdown (TIK-4 TikTok consent gate)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockView = MARKETING_VIEW
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue({
      workflow: 'MARKETING',
      currentStatus: 'DRAFT',
      transitions: POST_TRANSITIONS,
    })
  })

  async function renderPost(reason: string | null) {
    render(
      <TikTokPublishGateProvider reason={reason}>
        <StatusDropdown {...baseProps} workflowSlug="MARKETING" userRole="CREATOR" />
      </TikTokPublishGateProvider>
    )
    await waitFor(() => expect(screen.getByText('Submit for approval')).toBeInTheDocument())
  }

  it('disables the review-gated option and explains why when consent is missing', async () => {
    await renderPost(GATE_REASON)

    const submit = itemFor('Submit for approval')
    expect(submit).toHaveAttribute('aria-disabled', 'true')
    expect(submit).toHaveAttribute('title', GATE_REASON)
    expect(submit).toHaveTextContent(GATE_REASON)
  })

  it('does not transition and surfaces the reason when the blocked option is selected anyway', async () => {
    await renderPost(GATE_REASON)

    fireEvent.click(itemFor('Submit for approval'))

    expect(apiPatch).not.toHaveBeenCalled()
    expect(baseProps.onStatusChanged).not.toHaveBeenCalled()
    expect(toastError).toHaveBeenCalledWith(GATE_REASON)
  })

  it('enables the review-gated option and transitions once consent is given', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({})
    await renderPost(null)

    const submit = itemFor('Submit for approval')
    expect(submit).not.toHaveAttribute('aria-disabled')
    expect(submit).not.toHaveTextContent(GATE_REASON)

    fireEvent.click(submit)

    await waitFor(() =>
      expect(apiPatch).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/work-items/issue-1',
        { status: 'IN_REVIEW' },
        'tok'
      )
    )
    expect(toastError).not.toHaveBeenCalled()
  })

  it('leaves a non-review-gated move untouched while the gate is unmet', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({})
    await renderPost(GATE_REASON)

    const archive = itemFor('Archive')
    expect(archive).not.toHaveAttribute('aria-disabled')

    fireEvent.click(archive)

    await waitFor(() =>
      expect(apiPatch).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/work-items/issue-1',
        { status: 'ARCHIVED' },
        'tok'
      )
    )
    expect(toastError).not.toHaveBeenCalled()
  })

  it('leaves a Post with no TikTok target unaffected (no gate in context)', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({})
    render(<StatusDropdown {...baseProps} workflowSlug="MARKETING" userRole="CREATOR" />)
    await waitFor(() => expect(screen.getByText('Submit for approval')).toBeInTheDocument())

    const submit = itemFor('Submit for approval')
    expect(submit).not.toHaveAttribute('aria-disabled')

    fireEvent.click(submit)

    await waitFor(() =>
      expect(apiPatch).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/work-items/issue-1',
        { status: 'IN_REVIEW' },
        'tok'
      )
    )
  })

  it('leaves an ENGINEERING work item unaffected even with a gate reason present', async () => {
    mockView = ENGINEERING_VIEW
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue({
      workflow: 'ENGINEERING',
      currentStatus: 'DRAFT',
      transitions: [{ toStatus: 'IN_REVIEW', label: 'Submit for review' }],
    })
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({})

    render(
      <TikTokPublishGateProvider reason={GATE_REASON}>
        <StatusDropdown {...baseProps} userRole="CREATOR" />
      </TikTokPublishGateProvider>
    )
    await waitFor(() => expect(screen.getByText('Submit for review')).toBeInTheDocument())

    const submit = itemFor('Submit for review')
    expect(submit).not.toHaveAttribute('aria-disabled')

    fireEvent.click(submit)

    await waitFor(() => expect(apiPatch).toHaveBeenCalled())
    expect(toastError).not.toHaveBeenCalled()
  })
})
