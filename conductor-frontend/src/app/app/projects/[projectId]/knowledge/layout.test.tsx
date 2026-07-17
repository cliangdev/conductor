import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { vi } from 'vitest'
import type { KnowledgePageView } from '@/lib/knowledge-api'

// Plain (non-vi.fn) stub so rejected-promise paths aren't flagged as unhandled.
let getKnowledgeIndexBehavior: () => Promise<KnowledgePageView> = () =>
  Promise.resolve({ path: 'index.md', version: 0, type: 'index', content: '# Index\n' })

const push = vi.fn()
let pathname = '/app/projects/proj-1/knowledge'
const searchParams = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push }),
  usePathname: () => pathname,
  useSearchParams: () => searchParams,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

vi.mock('@/lib/knowledge-api', () => ({
  getKnowledgeIndex: (...args: unknown[]) => getKnowledgeIndexBehavior.call(null, ...(args as [])),
}))

vi.mock('@/components/knowledge/KnowledgeSearch', () => ({
  KnowledgeSearch: () => <div data-testid="knowledge-search" />,
}))

import KnowledgeLayout from './layout'

describe('KnowledgeLayout rail', () => {
  beforeEach(() => {
    push.mockClear()
    pathname = '/app/projects/proj-1/knowledge'
    searchParams.delete('path')
    getKnowledgeIndexBehavior = () =>
      Promise.resolve({ path: 'index.md', version: 0, type: 'index', content: '# Index\n' })
  })

  it('marks the Index button as the current page via aria-current', async () => {
    render(<KnowledgeLayout>content</KnowledgeLayout>)
    const indexButton = await screen.findByRole('button', { name: /index/i })
    expect(indexButton).toHaveAttribute('aria-current', 'page')

    const activityButton = screen.getByRole('button', { name: /activity/i })
    expect(activityButton).not.toHaveAttribute('aria-current')
  })

  it('marks the Activity button as current when viewing log.md', async () => {
    pathname = '/app/projects/proj-1/knowledge/page'
    searchParams.set('path', 'log.md')
    render(<KnowledgeLayout>content</KnowledgeLayout>)

    const activityButton = await screen.findByRole('button', { name: /activity/i })
    expect(activityButton).toHaveAttribute('aria-current', 'page')
    const indexButton = screen.getByRole('button', { name: /index/i })
    expect(indexButton).not.toHaveAttribute('aria-current')
  })

  it('shows a quiet empty tree (no error) when the index loads with zero pages', async () => {
    render(<KnowledgeLayout>content</KnowledgeLayout>)
    await waitFor(() => expect(screen.queryByText(/couldn.t load/i)).not.toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows an error notice with a Retry button when the index fetch fails, and recovers on retry', async () => {
    getKnowledgeIndexBehavior = () => Promise.reject(new Error('network down'))
    render(<KnowledgeLayout>content</KnowledgeLayout>)

    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn.t load knowledge pages/i)
    const retryButton = screen.getByRole('button', { name: /retry/i })

    getKnowledgeIndexBehavior = () =>
      Promise.resolve({
        path: 'index.md',
        version: 0,
        type: 'index',
        content: '* [Schema Guide](/_schema.md) (type: project)\n',
      })
    fireEvent.click(retryButton)

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(await screen.findByText('Schema Guide')).toBeInTheDocument()
  })
})
