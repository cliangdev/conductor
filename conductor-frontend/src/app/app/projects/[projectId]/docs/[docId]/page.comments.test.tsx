import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

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
vi.mock('@/components/docs/DocViewer', () => ({
  DocViewer: ({
    doc,
    focusCommentId,
    onFocusHandled,
    onDocumentChanged,
  }: {
    doc: { content?: string; contentHash?: string }
    focusCommentId?: string | null
    onFocusHandled?: () => void
    onDocumentChanged?: () => void
  }) => (
    <div>
      <output data-testid="focus">{focusCommentId ?? 'none'}</output>
      <output data-testid="hash">{doc.contentHash}</output>
      <output data-testid="content">{doc.content}</output>
      <button onClick={() => onFocusHandled?.()}>focused</button>
      <button onClick={() => onDocumentChanged?.()}>changed</button>
    </div>
  ),
}))

import DocDetailPage from './page'

const doc = { id: 'pd-1', projectId: 'proj-1', title: 'Plan', content: 'old text', contentHash: 'h0' }

describe('doc page comments wiring', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getDoc.mockResolvedValue(doc)
  })
  afterEach(() => window.history.replaceState(null, '', '/'))

  it('passes ?comment= to the viewer as the thread to focus, then removes it from the address', async () => {
    window.history.replaceState(null, '', '/app/projects/proj-1/docs/pd-1?comment=c42')
    render(<DocDetailPage />)
    await waitFor(() => expect(screen.getByTestId('focus')).toHaveTextContent('c42'))
    fireEvent.click(screen.getByText('focused'))
    await waitFor(() => expect(screen.getByTestId('focus')).toHaveTextContent('none'))
    expect(window.location.search).toBe('')
    expect(window.location.pathname).toBe('/app/projects/proj-1/docs/pd-1')
  })

  it('has nothing to focus without the parameter', async () => {
    render(<DocDetailPage />)
    await screen.findByTestId('focus')
    expect(screen.getByTestId('focus')).toHaveTextContent('none')
  })

  it('reloads the doc (content and hash) when the viewer says it changed', async () => {
    render(<DocDetailPage />)
    await screen.findByTestId('hash')
    getDoc.mockResolvedValue({ ...doc, content: 'agent edited text', contentHash: 'h1' })
    fireEvent.click(screen.getByText('changed'))
    await waitFor(() => expect(screen.getByTestId('hash')).toHaveTextContent('h1'))
    expect(screen.getByTestId('content')).toHaveTextContent('agent edited text')
  })
})
