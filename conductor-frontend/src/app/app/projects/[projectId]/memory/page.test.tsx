import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

// Modal/Sheet are @base-ui/react/dialog-backed and depend on browser APIs jsdom doesn't provide —
// stand in with a plain div (same approach as the secrets settings page test), rendering `footer`
// too so ConfirmModal's Confirm/Cancel buttons are reachable.
vi.mock('@/components/ui/modal', () => ({
  Modal: ({
    open,
    children,
    title,
    footer,
  }: {
    open: boolean
    children: React.ReactNode
    title: string
    footer?: React.ReactNode
  }) =>
    open ? (
      <div data-testid="modal" data-title={title}>
        <h2>{title}</h2>
        {children}
        {footer}
      </div>
    ) : null,
}))

vi.mock('@/components/ui/sheet', () => ({
  Sheet: ({ open, children, title }: { open: boolean; children: React.ReactNode; title: string }) =>
    open ? (
      <div data-testid="sheet" data-title={title}>
        <h2>{title}</h2>
        {children}
      </div>
    ) : null,
}))

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api')
  return {
    ...actual,
    listAgents: vi.fn().mockResolvedValue([]),
  }
})

vi.mock('@/lib/memory-api', () => ({
  listMemories: vi.fn(),
  getMemoryCounts: vi.fn(),
  createMemory: vi.fn(),
  getMemory: vi.fn(),
  updateMemory: vi.fn(),
  deleteMemory: vi.fn(),
}))

import * as memoryApi from '@/lib/memory-api'
import MemoryPage from './page'

const mockShowToast = vi.fn()

function memory(overrides: Partial<memoryApi.MemoryView> = {}): memoryApi.MemoryView {
  return {
    id: 'mem-1',
    content: 'The team prefers feature branches over trunk-based development.',
    type: 'preference',
    status: 'active',
    importance: 6,
    agentId: null,
    sourceConversationId: null,
    validFrom: '2026-08-01T00:00:00Z',
    validTo: null,
    supersededBy: null,
    promotedAt: null,
    accessCount: 2,
    lastAccessedAt: '2026-08-10T00:00:00Z',
    createdAt: '2026-08-01T00:00:00Z',
    ...overrides,
  }
}

describe('MemoryPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(memoryApi.listMemories).mockResolvedValue({ items: [memory()], total: 1 })
    vi.mocked(memoryApi.getMemoryCounts).mockResolvedValue({
      liveTotal: 1,
      raw: 0,
      consolidated: 1,
      superseded: 0,
    })
  })

  it('renders the list of memories with type and status badges', async () => {
    render(<MemoryPage />)
    const content = await screen.findByText(/team prefers feature branches/i)
    const row = content.closest('button') as HTMLElement
    expect(within(row).getByText('preference')).toBeInTheDocument()
    expect(within(row).getByText('Active')).toBeInTheDocument()
    expect(within(row).getByText('Importance 6/10')).toBeInTheDocument()
  })

  it('shows the counts summary from /counts', async () => {
    render(<MemoryPage />)
    await screen.findByText(/team prefers feature branches/i)
    expect(screen.getByText(/1 memory · 0 awaiting consolidation · 0 superseded/i)).toBeInTheDocument()
  })

  it('shows an empty state when there are no memories', async () => {
    vi.mocked(memoryApi.listMemories).mockResolvedValue({ items: [], total: 0 })
    render(<MemoryPage />)
    expect(await screen.findByText(/no memories yet/i)).toBeInTheDocument()
  })

  it('shows an error state with retry', async () => {
    vi.mocked(memoryApi.listMemories).mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }))
    render(<MemoryPage />)
    expect(await screen.findByRole('alert')).toBeInTheDocument()

    vi.mocked(memoryApi.listMemories).mockResolvedValue({ items: [memory()], total: 1 })
    fireEvent.click(screen.getByRole('button', { name: /retry/i }))
    expect(await screen.findByText(/team prefers feature branches/i)).toBeInTheDocument()
  })

  it('changing the status filter re-fetches with the status param', async () => {
    render(<MemoryPage />)
    await screen.findByText(/team prefers feature branches/i)

    fireEvent.change(screen.getByLabelText(/filter by status/i), { target: { value: 'raw' } })

    await waitFor(() => {
      const lastCall = vi.mocked(memoryApi.listMemories).mock.calls.at(-1)
      expect(lastCall?.[2]).toMatchObject({ status: 'raw' })
    })
  })

  it('changing the type filter re-fetches with the type param', async () => {
    render(<MemoryPage />)
    await screen.findByText(/team prefers feature branches/i)

    fireEvent.change(screen.getByLabelText(/filter by type/i), { target: { value: 'decision' } })

    await waitFor(() => {
      const lastCall = vi.mocked(memoryApi.listMemories).mock.calls.at(-1)
      expect(lastCall?.[2]).toMatchObject({ type: 'decision' })
    })
  })

  it('debounces the search box and only queries once 2+ characters are typed', async () => {
    render(<MemoryPage />)
    await screen.findByText(/team prefers feature branches/i)
    vi.mocked(memoryApi.listMemories).mockClear()

    fireEvent.change(screen.getByLabelText(/search memory content/i), { target: { value: 'te' } })

    await waitFor(
      () => {
        const lastCall = vi.mocked(memoryApi.listMemories).mock.calls.at(-1)
        expect(lastCall?.[2]).toMatchObject({ q: 'te' })
      },
      { timeout: 1000 },
    )
  })

  it('creating a memory calls createMemory and shows it in the list', async () => {
    vi.mocked(memoryApi.createMemory).mockResolvedValue(
      memory({ id: 'mem-new', content: 'New durable fact from a conversation.', type: 'fact', importance: 5 }),
    )

    render(<MemoryPage />)
    await screen.findByText(/team prefers feature branches/i)

    fireEvent.click(screen.getByRole('button', { name: /add memory/i }))
    const modal = await screen.findByTestId('modal')

    fireEvent.change(within(modal).getByLabelText(/^content$/i), {
      target: { value: 'New durable fact from a conversation.' },
    })
    fireEvent.click(within(modal).getByRole('button', { name: /^add memory$/i }))

    await waitFor(() => {
      expect(memoryApi.createMemory).toHaveBeenCalledWith(
        'proj-1',
        { content: 'New durable fact from a conversation.', type: 'fact', importance: 5 },
        'test-token',
      )
    })
    expect(await screen.findByText('New durable fact from a conversation.')).toBeInTheDocument()
    expect(mockShowToast).toHaveBeenCalledWith('Memory created')
  })

  it('opens the detail panel on row click, and the delete confirm flow removes the memory', async () => {
    vi.mocked(memoryApi.getMemory).mockResolvedValue({ ...memory(), history: [] })
    vi.mocked(memoryApi.deleteMemory).mockResolvedValue(undefined)

    render(<MemoryPage />)
    fireEvent.click(await screen.findByText(/team prefers feature branches/i))

    const sheet = await screen.findByTestId('sheet')
    expect(within(sheet).getByRole('button', { name: /^edit$/i })).toBeEnabled()

    fireEvent.click(within(sheet).getByRole('button', { name: /^delete$/i }))
    const confirmModal = await screen.findByTestId('modal')
    fireEvent.click(within(confirmModal).getByRole('button', { name: /^delete$/i }))

    await waitFor(() => {
      expect(memoryApi.deleteMemory).toHaveBeenCalledWith('proj-1', 'mem-1', 'test-token')
    })
    expect(mockShowToast).toHaveBeenCalledWith('Memory deleted')
  })

  it('a superseded memory is muted in the list and its Edit action is disabled in detail', async () => {
    const superseded = memory({
      id: 'mem-2',
      status: 'superseded',
      validTo: '2026-08-05T00:00:00Z',
      supersededBy: 'mem-3',
    })
    vi.mocked(memoryApi.listMemories).mockResolvedValue({ items: [superseded], total: 1 })
    vi.mocked(memoryApi.getMemory).mockResolvedValue({ ...superseded, history: [] })

    render(<MemoryPage />)
    const content = await screen.findByText(/team prefers feature branches/i)
    const row = content.closest('button') as HTMLElement
    expect(within(row).getByText('Superseded')).toBeInTheDocument()
    expect(row.querySelector('p')).toHaveClass('text-muted-foreground')

    fireEvent.click(content)
    const sheet = await screen.findByTestId('sheet')
    expect(within(sheet).getByRole('button', { name: /^edit$/i })).toBeDisabled()
  })

  it('renders supersession history in the detail panel', async () => {
    const older = memory({ id: 'mem-old', content: 'An earlier version of this fact.', createdAt: '2026-07-01T00:00:00Z' })
    vi.mocked(memoryApi.getMemory).mockResolvedValue({ ...memory(), history: [older] })

    render(<MemoryPage />)
    fireEvent.click(await screen.findByText(/team prefers feature branches/i))

    const sheet = await screen.findByTestId('sheet')
    expect(await within(sheet).findByText(/earlier version of this fact/i)).toBeInTheDocument()
  })
})
