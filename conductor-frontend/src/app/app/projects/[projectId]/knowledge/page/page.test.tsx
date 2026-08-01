import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { vi } from 'vitest'
import type { KnowledgePageDismissResult, KnowledgePageRevisionView, KnowledgePageView } from '@/lib/knowledge-api'

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled -- see the reference
// note on vi.fn + rejected promises in reference_vitest_rejected_promise_mock.
let getKnowledgePageBehavior: (projectId: string, path: string) => Promise<KnowledgePageView | null> = () =>
  Promise.resolve(basePage())
let listKnowledgeRevisionsBehavior: (
  projectId: string,
  path: string
) => Promise<KnowledgePageRevisionView[]> = () => Promise.resolve([])
let dismissKnowledgePageBehavior: (
  projectId: string,
  body: { path: string; baseVersion: number; reason: string },
  token: string
) => Promise<KnowledgePageDismissResult> = () =>
  Promise.resolve({
    path: 'engineering/architecture.md',
    version: 4,
    curationPagePath: 'engineering/_curation.md',
    curationPageVersion: 2,
  })
// Records dismissKnowledgePage call args for assertions, without wrapping the stub itself in vi.fn().
let dismissKnowledgePageCalls: Array<[string, { path: string; baseVersion: number; reason: string }, string]> = []

const push = vi.fn()
const showToast = vi.fn()
const searchParams = new URLSearchParams({ path: 'engineering/architecture.md' })

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push }),
  useSearchParams: () => searchParams,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast }),
}))

// Flatten the modal so ConfirmModal's content is always in the DOM when open -- same approach as
// workflows/[workflowId]/runs/page.test.tsx (the real base-ui Dialog is portal-based).
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
    footer: React.ReactNode
  }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
        {children}
        {footer}
      </div>
    ) : null,
}))

vi.mock('@/lib/knowledge-api', async () => ({
  // Preserve real exports (KNOWLEDGE_LIBRARIAN_SLUG etc.) — components under test import
  // constants from this module, not just the network functions overridden below.
  ...(await vi.importActual<typeof import('@/lib/knowledge-api')>('@/lib/knowledge-api')),
  getKnowledgePage: (...args: unknown[]) => getKnowledgePageBehavior.call(null, ...(args as [string, string])),
  listKnowledgeRevisions: (...args: unknown[]) =>
    listKnowledgeRevisionsBehavior.call(null, ...(args as [string, string])),
  dismissKnowledgePage: (...args: unknown[]) => {
    dismissKnowledgePageCalls.push(args as [string, { path: string; baseVersion: number; reason: string }, string])
    return dismissKnowledgePageBehavior.call(
      null,
      ...(args as [string, { path: string; baseVersion: number; reason: string }, string]),
    )
  },
}))

vi.mock('@/lib/format', () => ({
  // Identity-ish stub so the two revisions in the race test are distinguishable in the DOM.
  timeAgo: (iso: string) => `ts:${iso}`,
}))

vi.mock('@/components/markdown/MarkdownRenderer', () => ({
  MarkdownRenderer: ({ content }: { content: string }) => <div data-testid="markdown">{content}</div>,
}))

vi.mock('@/components/knowledge/KnowledgeHistoryPanel', () => ({
  KnowledgeHistoryPanel: () => <div data-testid="history-panel" />,
}))

import KnowledgePageRoute from './page'

function basePage(): KnowledgePageView {
  return {
    path: 'engineering/architecture.md',
    version: 3,
    type: 'component',
    title: 'Architecture',
    description: 'System architecture overview',
    content: '# Architecture\n\nSome body text.',
  }
}

describe('Knowledge page view', () => {
  beforeEach(() => {
    push.mockClear()
    showToast.mockClear()
    getKnowledgePageBehavior = () => Promise.resolve(basePage())
    listKnowledgeRevisionsBehavior = () => Promise.resolve([])
    dismissKnowledgePageBehavior = () =>
      Promise.resolve({
        path: 'engineering/architecture.md',
        version: 4,
        curationPagePath: 'engineering/_curation.md',
        curationPageVersion: 2,
      })
    dismissKnowledgePageCalls = []
  })

  it('renders the title, type badge, path, and markdown content', async () => {
    render(<KnowledgePageRoute />)

    expect(await screen.findByText('Architecture')).toBeInTheDocument()
    // StatusBadge humanizes the raw `type` string ("component" → "Component").
    expect(screen.getByText('Component')).toBeInTheDocument()
    expect(screen.getByText('engineering/architecture.md')).toBeInTheDocument()
    expect(screen.getByText(/maintained by librarian/i)).toBeInTheDocument()
    expect(screen.getByTestId('markdown')).toHaveTextContent('Some body text.')
  })

  it('shows the revised-at line once the latest revision loads', async () => {
    listKnowledgeRevisionsBehavior = () =>
      Promise.resolve([
        { version: 3, changeKind: 'UPDATE', createdAt: new Date().toISOString(), actor: { kind: 'agent', id: 'knowledge-librarian' } },
      ])
    render(<KnowledgePageRoute />)

    expect(await screen.findByText(/maintained by librarian · revised/i)).toBeInTheDocument()
  })

  it('shows a not-found message when the page does not exist', async () => {
    getKnowledgePageBehavior = () => Promise.resolve(null)
    render(<KnowledgePageRoute />)

    expect(await screen.findByText(/doesn.t exist/i)).toBeInTheDocument()
  })

  it('shows an error message when the fetch fails', async () => {
    getKnowledgePageBehavior = () => Promise.reject(new Error('boom'))
    render(<KnowledgePageRoute />)

    expect(await screen.findByRole('alert')).toHaveTextContent(/failed to load page/i)
  })

  it('opens the history panel when History is clicked', async () => {
    render(<KnowledgePageRoute />)
    const historyButton = await screen.findByRole('button', { name: /history/i })
    fireEvent.click(historyButton)

    await waitFor(() => expect(screen.getByTestId('history-panel')).toBeInTheDocument())
  })

  it('ignores a stale revisions response from the previously-viewed page after navigating away', async () => {
    // Simulates quick navigation A -> B where A's revisions request resolves after B's.
    function deferred<T>() {
      let resolve!: (value: T) => void
      const promise = new Promise<T>((res) => {
        resolve = res
      })
      return { promise, resolve }
    }

    const pathA = 'engineering/architecture.md'
    const pathB = 'engineering/other.md'
    const revisionsA = deferred<KnowledgePageRevisionView[]>()
    const revisionsB = deferred<KnowledgePageRevisionView[]>()

    getKnowledgePageBehavior = (_projectId, path) => Promise.resolve({ ...basePage(), path })
    listKnowledgeRevisionsBehavior = (_projectId, path) => (path === pathA ? revisionsA.promise : revisionsB.promise)

    searchParams.set('path', pathA)
    const { rerender } = render(<KnowledgePageRoute />)
    await screen.findByText('Architecture')

    // Navigate to page B before A's revisions request has resolved.
    searchParams.set('path', pathB)
    rerender(<KnowledgePageRoute />)
    await waitFor(() => expect(screen.getByText('engineering/other.md')).toBeInTheDocument())

    // B's (later-issued) revisions response lands first, then A's stale one arrives after.
    revisionsB.resolve([{ version: 5, changeKind: 'UPDATE', createdAt: 'B_TIME' }])
    await screen.findByText(/ts:B_TIME/)
    revisionsA.resolve([{ version: 1, changeKind: 'UPDATE', createdAt: 'A_TIME' }])

    // Give the stale A promise a tick to (incorrectly) apply if the guard were missing.
    await new Promise((r) => setTimeout(r, 0))
    expect(screen.queryByText(/ts:A_TIME/)).not.toBeInTheDocument()
    expect(screen.getByText(/ts:B_TIME/)).toBeInTheDocument()
  })

  describe('Not worth filing', () => {
    it('renders next to History', async () => {
      render(<KnowledgePageRoute />)
      await screen.findByText('Architecture')

      expect(screen.getByRole('button', { name: /not worth filing/i })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /history/i })).toBeInTheDocument()
    })

    it('opens the modal when clicked', async () => {
      render(<KnowledgePageRoute />)
      await screen.findByText('Architecture')

      fireEvent.click(screen.getByRole('button', { name: /not worth filing/i }))

      expect(await screen.findByTestId('modal')).toBeInTheDocument()
      expect(screen.getByLabelText(/reason/i)).toBeInTheDocument()
    })

    it('disables confirm while the reason is blank, enables it once typed', async () => {
      render(<KnowledgePageRoute />)
      await screen.findByText('Architecture')
      fireEvent.click(screen.getByRole('button', { name: /not worth filing/i }))
      await screen.findByTestId('modal')

      const confirmButton = screen.getByRole('button', { name: /^remove page$/i })
      expect(confirmButton).toBeDisabled()

      fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: 'Nothing happened here.' } })
      expect(confirmButton).not.toBeDisabled()
    })

    it('submits with the page path, baseVersion, and typed reason', async () => {
      render(<KnowledgePageRoute />)
      await screen.findByText('Architecture')
      fireEvent.click(screen.getByRole('button', { name: /not worth filing/i }))
      await screen.findByTestId('modal')
      fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: 'Nothing happened here.' } })

      fireEvent.click(screen.getByRole('button', { name: /^remove page$/i }))

      await waitFor(() => expect(dismissKnowledgePageCalls).toHaveLength(1))
      const [projectId, body, token] = dismissKnowledgePageCalls[0]
      expect(projectId).toBe('proj-1')
      expect(body).toEqual({
        path: 'engineering/architecture.md',
        baseVersion: 3,
        reason: 'Nothing happened here.',
      })
      expect(token).toBe('token')
    })

    it('on success, toasts the curation path and routes back to the knowledge index', async () => {
      render(<KnowledgePageRoute />)
      await screen.findByText('Architecture')
      fireEvent.click(screen.getByRole('button', { name: /not worth filing/i }))
      await screen.findByTestId('modal')
      fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: 'Nothing happened here.' } })
      fireEvent.click(screen.getByRole('button', { name: /^remove page$/i }))

      await waitFor(() => expect(push).toHaveBeenCalledWith('/app/projects/proj-1/knowledge'))
      expect(showToast).toHaveBeenCalledWith(expect.stringContaining('engineering/_curation.md'))
    })

    it('on failure, toasts the error and leaves the page rendered', async () => {
      const conflictError = new Error('conflict') as Error & { detail?: string }
      conflictError.detail = 'This page changed since you opened it — reload and try again.'
      dismissKnowledgePageBehavior = () => Promise.reject(conflictError)

      render(<KnowledgePageRoute />)
      await screen.findByText('Architecture')
      fireEvent.click(screen.getByRole('button', { name: /not worth filing/i }))
      await screen.findByTestId('modal')
      fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: 'Nothing happened here.' } })
      fireEvent.click(screen.getByRole('button', { name: /^remove page$/i }))

      await waitFor(() =>
        expect(showToast).toHaveBeenCalledWith(
          'This page changed since you opened it — reload and try again.',
          'error',
        ),
      )
      expect(push).not.toHaveBeenCalled()
      expect(screen.getByText('Architecture')).toBeInTheDocument()
    })
  })
})
