import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/types/workItem'

const push = vi.fn()
const replace = vi.fn()
let searchParamsValue = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace }),
  usePathname: () => '/app/projects/proj-1/engineering/issues',
  useSearchParams: () => searchParamsValue,
}))
vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token', user: { id: 'user-1' } }),
}))
vi.mock('@/lib/api', () => ({
  apiGet: (url: string) => Promise.resolve(url.includes('/work-items?workflow=') ? issues : []),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (e: unknown, fallback: string) => (e instanceof Error && e.message ? e.message : fallback),
}))

const BASE_VIEW: WorkflowView = {
  slug: 'ENGINEERING',
  noun: 'Issue',
  area: 'ENGINEERING',
  defaultView: 'list',
  version: 1,
  types: ['PRD', 'TASK'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'DONE', label: 'Done', category: 'terminal' },
  ],
  transitions: [],
}
let VIEW: WorkflowView = BASE_VIEW

vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, useWorkflowView: () => VIEW }
})
vi.mock('@/components/overview/OverviewPanel', () => ({ OverviewPanel: () => <div data-testid="overview-panel-stub" /> }))
vi.mock('@/components/roadmap/RoadmapPanel', () => ({ RoadmapPanel: () => <div data-testid="roadmap-panel-stub" /> }))
vi.mock('@/components/marketing/insights/WhatsWorkingPanel', () => ({ WhatsWorkingPanel: () => <div data-testid="insights-panel-stub" /> }))

import { WorkItemListView } from './WorkItemListView'

const issues = [
  { id: 'i1', title: 'Zeta task', type: 'TASK', status: 'DRAFT', updatedAt: '2026-01-05T00:00:00Z', createdAt: '2026-01-01T00:00:00Z', displayId: 'COND-1' },
]

function renderList() {
  render(<WorkItemListView projectId="proj-1" slug="ENGINEERING" noun="Issue" />)
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  searchParamsValue = new URLSearchParams()
  VIEW = BASE_VIEW
})

describe('Plans and Roadmap panels', () => {
  it('shows Plans and Roadmap tabs on a PRD Workflow', async () => {
    renderList()
    expect(await screen.findByRole('tab', { name: /^Plans$/ })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /^Roadmap$/ })).toBeInTheDocument()
  })

  it('does not show them on a Workflow without a PRD type', async () => {
    VIEW = { ...BASE_VIEW, types: ['POST'] }
    renderList()
    await screen.findByRole('tab', { name: /^Active/ })
    expect(screen.queryByRole('tab', { name: /^Plans$/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /^Roadmap$/ })).not.toBeInTheDocument()
  })

  it('renders the overview panel and hides the toolbar and display toggle for ?view=plans', async () => {
    searchParamsValue = new URLSearchParams('view=plans')
    renderList()
    expect(await screen.findByTestId('overview-panel-stub')).toBeInTheDocument()
    expect(screen.queryByTestId('roadmap-panel-stub')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Filter/ })).not.toBeInTheDocument()
    expect(screen.queryByTitle('Board view')).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /^Plans$/, selected: true })).toBeInTheDocument()
  })

  it('renders the roadmap panel for ?view=roadmap', async () => {
    searchParamsValue = new URLSearchParams('view=roadmap')
    renderList()
    expect(await screen.findByTestId('roadmap-panel-stub')).toBeInTheDocument()
    expect(screen.queryByTestId('overview-panel-stub')).not.toBeInTheDocument()
  })

  it('writes the panel into the view param when its tab is clicked', async () => {
    renderList()
    await userEvent.click(await screen.findByRole('tab', { name: /^Plans$/ }))
    expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/engineering/issues?view=plans')
  })

  it('selects the requested panel before the Workflow view has loaded', async () => {
    VIEW = null as unknown as WorkflowView
    searchParamsValue = new URLSearchParams('view=roadmap')
    renderList()
    expect(await screen.findByTestId('roadmap-panel-stub')).toBeInTheDocument()
    expect(screen.queryByTestId('work-item-list')).not.toBeInTheDocument()
  })

  it('clears a stale panel param when Active is clicked', async () => {
    VIEW = { ...BASE_VIEW, types: ['POST'] }
    searchParamsValue = new URLSearchParams('view=plans')
    renderList()
    await userEvent.click(await screen.findByRole('tab', { name: /^Done/ }))
    await userEvent.click(screen.getByRole('tab', { name: /^Active/ }))
    expect(replace).toHaveBeenLastCalledWith('/app/projects/proj-1/engineering/issues')
  })

  it('falls back to the active list for ?view=plans on a Workflow without a PRD type', async () => {
    VIEW = { ...BASE_VIEW, types: ['POST'] }
    searchParamsValue = new URLSearchParams('view=plans')
    renderList()
    await waitFor(() => expect(screen.getByRole('tab', { name: /^Active/, selected: true })).toBeInTheDocument())
    expect(screen.queryByTestId('overview-panel-stub')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Filter/ })).toBeInTheDocument()
  })
})

describe("What's working panel", () => {
  const POSTS_VIEW: WorkflowView = { ...BASE_VIEW, slug: 'MARKETING', noun: 'Post', area: 'MARKETING', types: ['POST'], assetTypes: ['facebook_post'] }

  it('shows the tab on a Workflow that declares publish targets', async () => {
    VIEW = POSTS_VIEW
    renderList()
    expect(await screen.findByRole('tab', { name: /^What's working$/ })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /^Plans$/ })).not.toBeInTheDocument()
  })

  it('does not show it on an Issues / PRD Workflow', async () => {
    renderList()
    await screen.findByRole('tab', { name: /^Active/ })
    expect(screen.queryByRole('tab', { name: /What's working/ })).not.toBeInTheDocument()
  })

  it('deep-links ?view=insights to the panel, without the toolbar', async () => {
    VIEW = POSTS_VIEW
    searchParamsValue = new URLSearchParams('view=insights')
    renderList()
    expect(await screen.findByTestId('insights-panel-stub')).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /^What's working$/, selected: true })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Filter/ })).not.toBeInTheDocument()
  })

  it('renders the panel before the Workflow view has loaded', async () => {
    VIEW = null as unknown as WorkflowView
    searchParamsValue = new URLSearchParams('view=insights')
    renderList()
    expect(await screen.findByTestId('insights-panel-stub')).toBeInTheDocument()
  })

  it('ignores ?view=insights on a Workflow without publish targets', async () => {
    searchParamsValue = new URLSearchParams('view=insights')
    renderList()
    await waitFor(() => expect(screen.getByRole('tab', { name: /^Active/, selected: true })).toBeInTheDocument())
    expect(screen.queryByTestId('insights-panel-stub')).not.toBeInTheDocument()
  })

  it('writes view=insights when the tab is clicked', async () => {
    VIEW = POSTS_VIEW
    renderList()
    await userEvent.click(await screen.findByRole('tab', { name: /^What's working$/ }))
    expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/engineering/issues?view=insights')
  })
})
