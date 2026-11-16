import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'

const { getDoc, setDocTaskState } = vi.hoisted(() => ({ getDoc: vi.fn(), setDocTaskState: vi.fn() }))
vi.mock('@/lib/docs-api', () => ({ getDoc, setDocTaskState }))
vi.mock('@/lib/api', () => ({ apiErrorMessage: (_e: unknown, fallback: string) => fallback }))
vi.mock('next/navigation', () => ({ useParams: () => ({ projectId: 'proj-1', docId: 'pd-1' }) }))
vi.mock('next/link', () => ({ default: ({ children }: { children: React.ReactNode }) => <a>{children}</a> }))
vi.mock('@/contexts/AuthContext', () => ({ useAuth: () => ({ accessToken: 'tok', user: { id: 'u1', name: 'Ada' } }) }))
vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => true,
  usePermissions: () => ({ roles: ['ADMIN'] }),
}))
vi.mock('@/lib/perf', () => ({ markPageReady: () => {} }))
vi.mock('@/components/ui/toast', () => ({ toastError: vi.fn() }))
vi.mock('@/components/docs/DocHistoryPanel', () => ({ DocHistoryPanel: () => null }))
// The viewer is where comments take the hash from: show what it was given and let the test tick boxes.
vi.mock('@/components/docs/DocViewer', () => ({
  DocViewer: ({
    doc,
    onToggleTask,
  }: {
    doc: { content?: string; contentHash?: string }
    onToggleTask?: (line: number, checked: boolean) => void
  }) => (
    <div>
      <output data-testid="hash">{doc.contentHash}</output>
      <output data-testid="content">{doc.content}</output>
      <button onClick={() => onToggleTask?.(1, true)}>tick 1</button>
      <button onClick={() => onToggleTask?.(2, true)}>tick 2</button>
    </div>
  ),
}))

import DocDetailPage from './page'

const base = {
  id: 'pd-1',
  projectId: 'proj-1',
  title: 'Plan',
  content: '- [ ] one\n- [ ] two\n',
  contentHash: 'h0',
}

function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => (resolve = r))
  return { promise, resolve }
}

describe('doc page task toggles', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getDoc.mockResolvedValue(base)
  })

  it('takes the new contentHash from the toggle response, so a comment written next carries it', async () => {
    setDocTaskState.mockResolvedValue({ ...base, content: '- [x] one\n- [ ] two\n', contentHash: 'h1' })
    render(<DocDetailPage />)
    await screen.findByTestId('hash')
    expect(screen.getByTestId('hash')).toHaveTextContent('h0')

    fireEvent.click(screen.getByText('tick 1'))
    await waitFor(() => expect(screen.getByTestId('hash')).toHaveTextContent('h1'))
    expect(screen.getByTestId('content')).toHaveTextContent('[x] one')
  })

  it('lets the newest reply win when two toggles come back out of order, and keeps both ticks', async () => {
    const first = deferred<unknown>()
    const second = deferred<unknown>()
    setDocTaskState.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    render(<DocDetailPage />)
    await screen.findByTestId('hash')

    fireEvent.click(screen.getByText('tick 1'))
    fireEvent.click(screen.getByText('tick 2'))
    await act(async () => {
      second.resolve({ ...base, contentHash: 'h2' })
    })
    await act(async () => {
      first.resolve({ ...base, contentHash: 'h1' }) // older, arrives late
    })
    expect(screen.getByTestId('hash')).toHaveTextContent('h2')
    expect(screen.getByTestId('content').textContent).toBe('- [x] one\n- [x] two\n')
  })

  it('leaves the hash alone when the toggle fails', async () => {
    setDocTaskState.mockRejectedValue(new Error('nope'))
    render(<DocDetailPage />)
    await screen.findByTestId('hash')
    fireEvent.click(screen.getByText('tick 1'))
    await waitFor(() => expect(setDocTaskState).toHaveBeenCalled())
    await act(async () => {})
    expect(screen.getByTestId('hash')).toHaveTextContent('h0')
  })
})
