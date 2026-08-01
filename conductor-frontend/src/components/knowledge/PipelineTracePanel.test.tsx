import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { PipelineTraceDto } from '@/lib/knowledge-api'

// Plain (non-vi.fn) stub so a rejected-promise path isn't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory.
let traceBehavior: () => Promise<PipelineTraceDto> = () => Promise.resolve({ nodes: [] })

vi.mock('@/lib/knowledge-api', async () => ({
  ...(await vi.importActual<typeof import('@/lib/knowledge-api')>('@/lib/knowledge-api')),
  getPipelineTrace: () => traceBehavior(),
}))

import { PipelineTracePanel } from './PipelineTracePanel'

describe('PipelineTracePanel', () => {
  beforeEach(() => {
    traceBehavior = () => Promise.resolve({ nodes: [] })
  })

  it('renders nothing (closed) when anchor is null', () => {
    render(<PipelineTracePanel projectId="proj-1" token="tok" anchor={null} onClose={vi.fn()} />)
    expect(screen.queryByText('Pipeline trace')).not.toBeInTheDocument()
  })

  it('renders each node with its stage label and status, oldest first', async () => {
    traceBehavior = () =>
      Promise.resolve({
        nodes: [
          { stage: 'WEBHOOKS', id: 'wh-1', status: 'PROCESSED', occurredAt: '2026-01-01T00:00:00Z', label: 'pull_request', degraded: false },
          { stage: 'INBOX', id: 'src-1', status: 'PROCESSED', occurredAt: '2026-01-01T00:01:00Z', label: 'github.pr_merged', degraded: false },
        ],
      })

    render(<PipelineTracePanel projectId="proj-1" token="tok" anchor={{ sourceId: 'src-1' }} onClose={vi.fn()} />)

    expect(await screen.findByText('Webhook')).toBeInTheDocument()
    expect(screen.getByText('Knowledge source')).toBeInTheDocument()
    expect(screen.getByText('pull_request')).toBeInTheDocument()
    expect(screen.getByText('github.pr_merged')).toBeInTheDocument()
  })

  it('renders a degraded node as "Purged by retention", visually distinct from a real status', async () => {
    traceBehavior = () =>
      Promise.resolve({
        nodes: [{ stage: 'INBOX', id: 'gone', status: null, occurredAt: null, label: null, degraded: true }],
      })

    render(<PipelineTracePanel projectId="proj-1" token="tok" anchor={{ sourceId: 'gone' }} onClose={vi.fn()} />)

    expect(await screen.findByText('Purged by retention')).toBeInTheDocument()
    // A degraded node must not also render a real StatusBadge dot/label for its (absent) status.
    expect(screen.queryByText('DEAD')).not.toBeInTheDocument()
  })

  it('shows an empty-state message when the trace has no nodes', async () => {
    render(<PipelineTracePanel projectId="proj-1" token="tok" anchor={{ sourceId: 'src-1' }} onClose={vi.fn()} />)
    expect(await screen.findByText('No trace data found for this item.')).toBeInTheDocument()
  })

  it('surfaces a load failure', async () => {
    traceBehavior = () => Promise.reject(new Error('boom'))
    render(<PipelineTracePanel projectId="proj-1" token="tok" anchor={{ sourceId: 'src-1' }} onClose={vi.fn()} />)
    await waitFor(() => expect(screen.getByText(/Failed to load trace|boom/)).toBeInTheDocument())
  })
})
