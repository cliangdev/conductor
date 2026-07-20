import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { KnowledgePageView, KnowledgeDomainDto } from '@/lib/knowledge-api'
import type { Agent } from '@/lib/api'

const mockShowToast = vi.fn()

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory.
let getKnowledgeIndexBehavior: () => Promise<KnowledgePageView> = () =>
  Promise.resolve({ path: 'index.md', version: 0, type: 'index', content: '# Index\n' })
let getKnowledgePagesBehavior: (paths: string[]) => Promise<KnowledgePageView[]> = () => Promise.resolve([])
let listKnowledgeDomainsBehavior: () => Promise<KnowledgeDomainDto[]> = () => Promise.resolve([])
let listAgentsBehavior: () => Promise<Agent[]> = () => Promise.resolve([])

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: () => true, refresh: vi.fn() }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/lib/knowledge-api', () => ({
  getKnowledgeIndex: (...args: unknown[]) => getKnowledgeIndexBehavior.call(null, ...(args as [])),
  getKnowledgePages: (_projectId: string, paths: string[]) => getKnowledgePagesBehavior(paths),
  enableKnowledge: vi.fn(),
  listKnowledgeDomains: () => listKnowledgeDomainsBehavior(),
}))

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api')
  return {
    ...actual,
    listAgents: () => listAgentsBehavior(),
  }
})

import KnowledgeIndexPage from './page'

function indexContent(bullets: string[]): string {
  return `# Index\n\n## /\n\n${bullets.join('\n')}\n`
}

function logContent(entries: string): KnowledgePageView {
  return { path: 'log.md', version: 0, type: 'log', content: entries }
}

function domain(overrides: Partial<KnowledgeDomainDto> = {}): KnowledgeDomainDto {
  return {
    slug: 'engineering',
    displayName: 'Engineering',
    pathPrefix: 'engineering/',
    schemaPagePath: 'engineering/_schema.md',
    sourceTypePatterns: [],
    owningAgentSlug: null,
    state: 'ACTIVE',
    pendingCount: 0,
    processingCount: 0,
    processedCount: 0,
    ...overrides,
  }
}

describe('KnowledgeIndexPage (composed Home)', () => {
  beforeEach(() => {
    mockShowToast.mockClear()
    getKnowledgeIndexBehavior = () =>
      Promise.resolve({
        path: 'index.md',
        version: 0,
        type: 'index',
        content: indexContent(['* [Design Doc](/engineering/design.md) (type: architecture)']),
      })
    getKnowledgePagesBehavior = () => Promise.resolve([])
    listKnowledgeDomainsBehavior = () => Promise.resolve([])
    listAgentsBehavior = () => Promise.resolve([])
  })

  it('shows the page count and Librarian subtitle', async () => {
    render(<KnowledgeIndexPage />)

    expect(await screen.findByText('Knowledge')).toBeInTheDocument()
    expect(screen.getByText(/1 page · maintained by the Librarian/)).toBeInTheDocument()
  })

  it('adds a "last updated" clause when the log has entries', async () => {
    getKnowledgePagesBehavior = () =>
      Promise.resolve([logContent('## 2026-07-19\n\n* **Update**: engineering/design.md\n')])

    render(<KnowledgeIndexPage />)

    expect(await screen.findByText(/last updated/)).toBeInTheDocument()
  })

  it('omits the "last updated" clause when the log has no entries', async () => {
    getKnowledgePagesBehavior = () => Promise.resolve([logContent('# Log\n')])

    render(<KnowledgeIndexPage />)

    await screen.findByText('Knowledge')
    expect(screen.queryByText(/last updated/)).not.toBeInTheDocument()
  })

  it('renders "Recently updated" from mocked log entries matched against the index', async () => {
    getKnowledgeIndexBehavior = () =>
      Promise.resolve({
        path: 'index.md',
        version: 0,
        type: 'index',
        content: indexContent(['* [Design Doc](/engineering/design.md) (type: architecture)']),
      })
    getKnowledgePagesBehavior = () =>
      Promise.resolve([
        logContent('## 2026-07-19\n\n* **Update**: engineering/design.md ← slack://C123/p456\n'),
      ])

    render(<KnowledgeIndexPage />)

    expect(await screen.findByText('Recently updated')).toBeInTheDocument()
    expect(screen.getByText('Design Doc')).toBeInTheDocument()
    expect(screen.getByText('engineering')).toBeInTheDocument()
    expect(screen.getByText('updated')).toBeInTheDocument()
    expect(screen.getByText(/from slack:\/\/C123\/p456/)).toBeInTheDocument()
  })

  it('skips a log entry whose page no longer exists in the content-page set', async () => {
    getKnowledgeIndexBehavior = () =>
      Promise.resolve({
        path: 'index.md',
        version: 0,
        type: 'index',
        content: indexContent(['* [Design Doc](/engineering/design.md) (type: architecture)']),
      })
    getKnowledgePagesBehavior = () =>
      Promise.resolve([logContent('## 2026-07-19\n\n* **Update**: engineering/deleted.md\n')])

    render(<KnowledgeIndexPage />)

    await screen.findByText('Knowledge')
    expect(screen.queryByText('Recently updated')).not.toBeInTheDocument()
  })

  it('caps "Recently updated" at 5 entries', async () => {
    const bullets = Array.from({ length: 8 }, (_, i) => `* [Page ${i}](/notes/p${i}.md) (type: note)`)
    getKnowledgeIndexBehavior = () =>
      Promise.resolve({ path: 'index.md', version: 0, type: 'index', content: indexContent(bullets) })
    const logLines = Array.from({ length: 8 }, (_, i) => `* **Update**: notes/p${i}.md`).join('\n')
    getKnowledgePagesBehavior = () => Promise.resolve([logContent(`## 2026-07-19\n\n${logLines}\n`)])

    render(<KnowledgeIndexPage />)

    await screen.findByText('Recently updated')
    expect(screen.getAllByText('updated')).toHaveLength(5)
  })

  it('renders an area card for an ACTIVE domain with pages under its prefix', async () => {
    getKnowledgeIndexBehavior = () =>
      Promise.resolve({
        path: 'index.md',
        version: 0,
        type: 'index',
        content: indexContent(['* [Design Doc](/engineering/design.md) (type: architecture)']),
      })
    listKnowledgeDomainsBehavior = () => Promise.resolve([domain()])

    render(<KnowledgeIndexPage />)

    expect(await screen.findByText('Browse by area')).toBeInTheDocument()
    const link = screen.getByText('Engineering').closest('a')
    expect(link).toHaveTextContent('1 page')
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/knowledge/page?path=engineering%2Fdesign.md')
  })

  it('collapses empty areas into one muted card alongside pageful areas', async () => {
    getKnowledgeIndexBehavior = () =>
      Promise.resolve({
        path: 'index.md',
        version: 0,
        type: 'index',
        content: indexContent(['* [Design Doc](/engineering/design.md) (type: architecture)']),
      })
    listKnowledgeDomainsBehavior = () =>
      Promise.resolve([
        domain(),
        domain({ slug: 'legal', displayName: 'Legal', pathPrefix: 'legal/' }),
        domain({ slug: 'marketing', displayName: 'Marketing', pathPrefix: 'marketing/' }),
      ])

    render(<KnowledgeIndexPage />)

    await screen.findByText('Browse by area')
    expect(screen.getByText('Legal & Marketing')).toBeInTheDocument()
    expect(screen.getByText(/No pages yet/)).toBeInTheDocument()
  })

  it('skips the "Browse by area" section entirely when every ACTIVE area is empty', async () => {
    listKnowledgeDomainsBehavior = () => Promise.resolve([domain({ pathPrefix: 'nonexistent/' })])

    render(<KnowledgeIndexPage />)

    await screen.findByText('Knowledge')
    expect(screen.queryByText('Browse by area')).not.toBeInTheDocument()
  })

  it('omits both auxiliary sections without an error banner when their fetches fail', async () => {
    getKnowledgePagesBehavior = () => Promise.reject(new Error('boom'))
    listKnowledgeDomainsBehavior = () => Promise.reject(new Error('boom'))

    render(<KnowledgeIndexPage />)

    await waitFor(() => {
      expect(screen.queryByText('Recently updated')).not.toBeInTheDocument()
    })
    expect(screen.queryByText('Browse by area')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('still shows the empty-wiki admin state unchanged when the index has no pages', async () => {
    getKnowledgeIndexBehavior = () => Promise.resolve({ path: 'index.md', version: 0, type: 'index', content: '# Index\n' })

    render(<KnowledgeIndexPage />)

    expect(await screen.findByText('The knowledge base is empty')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /enable knowledge/i })).toBeInTheDocument()
  })
})
