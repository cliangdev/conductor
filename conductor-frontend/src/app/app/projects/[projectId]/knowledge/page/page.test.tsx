import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { vi } from 'vitest'
import type { KnowledgePageView } from '@/lib/knowledge-api'

// Plain (non-vi.fn) stub so rejected-promise paths aren't flagged as unhandled.
let getKnowledgePageBehavior: () => Promise<KnowledgePageView | null> = () =>
  Promise.resolve(basePage())

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

vi.mock('@/lib/knowledge-api', () => ({
  getKnowledgePage: (...args: unknown[]) => getKnowledgePageBehavior.call(null, ...(args as [])),
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
  })

  it('renders the title, type badge, path, and markdown content', async () => {
    render(<KnowledgePageRoute />)

    expect(await screen.findByText('Architecture')).toBeInTheDocument()
    expect(screen.getByText('component')).toBeInTheDocument()
    expect(screen.getByText('engineering/architecture.md')).toBeInTheDocument()
    expect(screen.getByTestId('markdown')).toHaveTextContent('Some body text.')
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
})
