import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { vi } from 'vitest'
import type { KnowledgePageRevisionView, KnowledgePageView } from '@/lib/knowledge-api'

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled.
let getKnowledgePageBehavior: (projectId: string, path: string) => Promise<KnowledgePageView | null> = () =>
  Promise.resolve(basePage())
let listKnowledgeRevisionsBehavior: (
  projectId: string,
  path: string
) => Promise<KnowledgePageRevisionView[]> = () => Promise.resolve([])

const push = vi.fn()
const searchParams = new URLSearchParams({ path: 'engineering/architecture.md' })

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push }),
  useSearchParams: () => searchParams,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

vi.mock('@/lib/knowledge-api', async () => ({
  // Preserve real exports (KNOWLEDGE_LIBRARIAN_SLUG etc.) — components under test import
  // constants from this module, not just the network functions overridden below.
  ...(await vi.importActual<typeof import('@/lib/knowledge-api')>('@/lib/knowledge-api')),
  getKnowledgePage: (...args: unknown[]) => getKnowledgePageBehavior.call(null, ...(args as [string, string])),
  listKnowledgeRevisions: (...args: unknown[]) =>
    listKnowledgeRevisionsBehavior.call(null, ...(args as [string, string])),
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
    getKnowledgePageBehavior = () => Promise.resolve(basePage())
    listKnowledgeRevisionsBehavior = () => Promise.resolve([])
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
})
