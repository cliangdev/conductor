import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) =>
    (err as { detail?: string })?.detail || fallback,
}))

const { mockCan } = vi.hoisted(() => ({ mockCan: vi.fn((_cap?: string) => true) }))
vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: (cap: string) => mockCan(cap),
}))

Object.assign(navigator, { clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } })

import { apiGet, apiPost } from '@/lib/api'
import { RendersPanel } from './RendersPanel'
import type { CreativeRenderRun } from './types'

const RENDERS = '/api/v2/projects/proj-1/marketing/creatives/cr-1/renders'
const LATEST = '/api/v2/projects/proj-1/marketing/creatives/cr-1/render-runs/latest'
const DISPATCH = '/api/v2/projects/proj-1/marketing/creatives/cr-1/render-runs'

function run(overrides: Partial<CreativeRenderRun> = {}): CreativeRenderRun {
  return { workflowId: 'wf-1', runId: 'run-1', status: 'RUNNING', ...overrides }
}

/** GET render-runs/latest returns the run (undefined = 204); GET renders returns []. */
function mockGets(getRun: () => CreativeRenderRun | null) {
  ;(apiGet as Mock).mockImplementation(async (path: string) => {
    if (path === RENDERS) return []
    if (path === LATEST) return getRun() ?? undefined
    throw new Error(`unexpected GET ${path}`)
  })
}

function setup(props: Partial<React.ComponentProps<typeof RendersPanel>> = {}) {
  return render(
    <RendersPanel
      projectId="proj-1"
      creativeId="cr-1"
      creativeDisplayId="12a"
      creativeVersion={3}
      creativeKind="MOTION"
      localRender
      webRender
      token="tok"
      registry={null}
      {...props}
    />,
  )
}

function apiError(status: number, code: string, extra: Record<string, unknown> = {}) {
  return Object.assign(new Error('boom'), { status, code, detail: `detail for ${code}`, ...extra })
}

describe('RendersPanel web render', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCan.mockReturnValue(true)
  })
  afterEach(() => vi.useRealTimers())

  it('shows a Render button that POSTs render-runs and then the rendering state with a run link', async () => {
    let current: CreativeRenderRun | null = null
    mockGets(() => current)
    ;(apiPost as Mock).mockImplementation(async () => {
      current = run()
      return { workflowId: 'wf-1', runId: 'run-1', status: 'PENDING' }
    })
    setup()
    const button = await screen.findByRole('button', { name: 'Render' })
    await userEvent.click(button)
    expect(apiPost).toHaveBeenCalledWith(DISPATCH, {}, 'tok')
    expect(await screen.findByText('Rendering…')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View run' })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/workflows/wf-1/runs/run-1',
    )
  })

  it('does not show Render when the Creative does not need a web render or the user cannot manage', async () => {
    mockGets(() => null)
    setup({ webRender: false })
    await screen.findByText('No renders yet')
    expect(screen.queryByRole('button', { name: 'Render' })).toBeNull()
  })

  it('hides Render without the creative.manage permission', async () => {
    mockCan.mockReturnValue(false)
    mockGets(() => null)
    setup()
    await screen.findByText('No renders yet')
    expect(screen.queryByRole('button', { name: 'Render' })).toBeNull()
  })

  it('shows "Waiting for a runner" with the daemon hint and docs link for AWAITING_RUNNER', async () => {
    mockGets(() => run({ status: 'PENDING_LOCAL_PICKUP', waitReason: 'AWAITING_RUNNER' }))
    setup()
    expect(await screen.findByText('Waiting for a runner')).toBeInTheDocument()
    expect(screen.getByText('conductor start')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Self-hosted setup' })).toHaveAttribute(
      'href',
      expect.stringContaining('docs/workflows.md#self-hosted-setup'),
    )
    expect(screen.getByRole('button', { name: 'Render' })).toBeDisabled()
  })

  it('shows "Render failed" with the error and a run link, and re-enables Render', async () => {
    mockGets(() => run({ status: 'FAILED', error: 'ffmpeg exited 1' }))
    setup()
    expect(await screen.findByText('Render failed')).toBeInTheDocument()
    expect(screen.getByText('ffmpeg exited 1')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View run' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Render' })).toBeEnabled()
  })

  it('treats a timed-out pickup as failed and allows rendering again', async () => {
    mockGets(() => run({ status: 'LOCAL_PICKUP_TIMEOUT' }))
    setup()
    expect(await screen.findByText('Render failed')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Render' })).toBeEnabled()
  })

  it('shows no run banner for a succeeded run and re-enables Render', async () => {
    mockGets(() => run({ status: 'SUCCESS' }))
    setup()
    await screen.findByText('No renders yet')
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith(LATEST, 'tok'))
    expect(screen.queryByTestId('render-run-status')).toBeNull()
    expect(screen.queryByTestId('render-run-failed')).toBeNull()
    expect(screen.getByRole('button', { name: 'Render' })).toBeEnabled()
  })

  it('refreshes the renders list when an active run settles', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let current: CreativeRenderRun = run()
    mockGets(() => current)
    setup()
    await screen.findByText('Rendering…')
    const rendersCalls = () => (apiGet as Mock).mock.calls.filter((c) => c[0] === RENDERS).length
    const before = rendersCalls()
    current = run({ status: 'SUCCESS' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5100)
    })
    await waitFor(() => expect(screen.queryByText('Rendering…')).toBeNull())
    expect(rendersCalls()).toBeGreaterThan(before + 1)
  })

  it('polls the Creative every 5 s while a run is active', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mockGets(() => run())
    setup()
    await screen.findByText('Rendering…')
    const creativeCalls = () => (apiGet as Mock).mock.calls.filter((c) => c[0] === LATEST).length
    const before = creativeCalls()
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5100)
    })
    expect(creativeCalls()).toBeGreaterThan(before)
  })

  it('204 from render-runs/latest shows no banner and no error', async () => {
    mockGets(() => null)
    setup()
    await screen.findByText('No renders yet')
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith(LATEST, 'tok'))
    expect(screen.queryByTestId('render-run-status')).toBeNull()
    expect(screen.queryByTestId('render-run-failed')).toBeNull()
    expect(screen.queryByTestId('render-run-error')).toBeNull()
  })

  it('a stale run-status error clears after a successful poll', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    let failing = true
    ;(apiGet as Mock).mockImplementation(async (path: string) => {
      if (path === RENDERS) return [{ id: 'r', state: 'RUNNING', requestedAt: new Date().toISOString(), frames: [], creativeVersion: 3 }]
      if (path === LATEST) {
        if (failing) throw Object.assign(new Error('x'), { detail: 'status down' })
        return run({ status: 'SUCCESS' })
      }
      throw new Error(`unexpected GET ${path}`)
    })
    setup()
    expect(await screen.findByText('status down')).toBeInTheDocument()
    failing = false
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5100)
    })
    await waitFor(() => expect(screen.queryByText('status down')).toBeNull())
  })

  it('a list error and a run-status error are independent', async () => {
    ;(apiGet as Mock).mockImplementation(async (path: string) => {
      if (path === RENDERS) throw Object.assign(new Error('x'), { detail: 'list down' })
      if (path === LATEST) throw Object.assign(new Error('x'), { detail: 'status down' })
      throw new Error(`unexpected GET ${path}`)
    })
    setup()
    expect(await screen.findByText('list down')).toBeInTheDocument()
    expect(await screen.findByText('status down')).toBeInTheDocument()
  })

  it('a run-status failure does not hide a successful renders list', async () => {
    ;(apiGet as Mock).mockImplementation(async (path: string) => {
      if (path === RENDERS) return []
      throw Object.assign(new Error('x'), { detail: 'status down' })
    })
    setup()
    expect(await screen.findByText('No renders yet')).toBeInTheDocument()
    expect(await screen.findByText('status down')).toBeInTheDocument()
  })

  it('guards against double clicks while the dispatch is in flight', async () => {
    mockGets(() => null)
    let resolve!: (v: unknown) => void
    ;(apiPost as Mock).mockImplementation(() => new Promise((r) => (resolve = r)))
    setup()
    const button = await screen.findByRole('button', { name: 'Render' })
    await userEvent.dblClick(button)
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: 'Starting…' })).toBeDisabled()
    await act(async () => resolve({ workflowId: 'wf-1', runId: 'run-1', status: 'PENDING' }))
  })

  it('409 RENDER_RUN_ACTIVE shows the existing run without an error', async () => {
    let current: CreativeRenderRun | null = null
    mockGets(() => current)
    ;(apiPost as Mock).mockImplementation(async () => {
      current = run({ runId: 'run-9' })
      throw apiError(409, 'RENDER_RUN_ACTIVE', {
        extensions: { workflowId: 'wf-1', runId: 'run-9' },
      })
    })
    setup()
    await userEvent.click(await screen.findByRole('button', { name: 'Render' }))
    expect(await screen.findByText('Rendering…')).toBeInTheDocument()
    expect(screen.queryByTestId('render-dispatch-error')).toBeNull()
    expect(screen.getByRole('link', { name: 'View run' })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/workflows/wf-1/runs/run-9',
    )
  })

  it('409 RENDER_WORKFLOW_DISABLED shows the message and a link to the workflow', async () => {
    mockGets(() => null)
    ;(apiPost as Mock).mockRejectedValue(
      apiError(409, 'RENDER_WORKFLOW_DISABLED', { extensions: { workflowId: 'wf-7' } }),
    )
    setup()
    await userEvent.click(await screen.findByRole('button', { name: 'Render' }))
    expect(await screen.findByText('detail for RENDER_WORKFLOW_DISABLED')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the workflow' })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/workflows/wf-7',
    )
    expect(screen.getByRole('button', { name: 'Render' })).toBeEnabled()
  })

  it('422 lists the violations', async () => {
    mockGets(() => null)
    ;(apiPost as Mock).mockRejectedValue(
      apiError(422, 'VALIDATION_FAILED', {
        violations: [
          { field: 'photoId', ruleId: 'r1', message: 'Pick a photo first' },
          { field: 'motion', ruleId: 'r2', message: 'Motion preset is required' },
        ],
      }),
    )
    setup()
    await userEvent.click(await screen.findByRole('button', { name: 'Render' }))
    expect(await screen.findByText('Pick a photo first')).toBeInTheDocument()
    expect(screen.getByText('Motion preset is required')).toBeInTheDocument()
  })

  it('other errors surface the problem detail and keep Render enabled', async () => {
    mockGets(() => null)
    ;(apiPost as Mock).mockRejectedValue(apiError(409, 'RENDER_WORKFLOW_NAME_TAKEN'))
    setup()
    await userEvent.click(await screen.findByRole('button', { name: 'Render' }))
    expect(await screen.findByText('detail for RENDER_WORKFLOW_NAME_TAKEN')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Render' })).toBeEnabled()
  })

  it('keeps the Claude Code / CLI copy as a secondary option', async () => {
    mockGets(() => null)
    setup()
    await screen.findByTestId('render-local-help')
    expect(screen.getByText('conductor creative render 12a')).toBeInTheDocument()
    expect(screen.getByText('Render creative 12a with render_creative')).toBeInTheDocument()
  })

  it('never dispatches on its own', async () => {
    mockGets(() => null)
    setup()
    await screen.findByText('No renders yet')
    expect(apiPost).not.toHaveBeenCalled()
  })
})
