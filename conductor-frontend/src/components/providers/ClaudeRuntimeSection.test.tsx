import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => mockCanMutate,
}))

vi.mock('@/components/ui/modal', () => ({
  Modal: ({ open, children, title }: { open: boolean; children: React.ReactNode; title: string }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
        {children}
      </div>
    ) : null,
}))

interface StubRuntimeTarget {
  id: string
  name: string
  provider: 'gcp-cloud-run'
  connectionId: string
  gcpProjectId: string
  region: string
  jobName: string
  image: string
  status: 'PROVISIONING' | 'ACTIVE' | 'ERROR'
  errorMessage?: string | null
  warnings?: string[] | null
  createdAt: string
  updatedAt: string
}

interface StubConfig {
  source: 'project-target' | 'builtin'
  runtimeTargetId?: string | null
  runtimeTarget?: StubRuntimeTarget | null
  builtinConfigured: boolean
}

// vitest 4 flags a vi.fn() mock whose implementation returns a rejected promise as an unhandled
// rejection even when the component awaits/catches it — drive rejections through plain, per-test
// behavior variables instead (see reference_vitest_rejected_promise_mock memory).
let getRuntimeBehavior: () => Promise<StubConfig> = () =>
  Promise.resolve({ source: 'builtin', runtimeTargetId: null, runtimeTarget: null, builtinConfigured: true })
let listTargetsBehavior: () => Promise<StubRuntimeTarget[]> = () => Promise.resolve([])
let listConnectionsBehavior: () => Promise<{ id: string; status: string; label?: string }[]> = () =>
  Promise.resolve([{ id: 'conn-1', status: 'ACTIVE', label: 'prod-sa' }])
let setRuntimeBehavior: () => Promise<StubConfig> = () =>
  Promise.resolve({ source: 'project-target', runtimeTargetId: 'target-1', runtimeTarget: activeTarget, builtinConfigured: true })
let verifyBehavior: () => Promise<{ provider: string; status: 'verified' | 'error'; checkedAt: string; checks: { name: string; status: string; message: string }[] }> =
  () => Promise.resolve({ provider: 'claude-code', status: 'verified', checkedAt: '2026-01-01T00:00:00Z', checks: [] })

vi.mock('@/lib/api', () => ({
  apiErrorMessage: (err: unknown, fallback: string) =>
    err && typeof err === 'object' && 'detail' in err ? String((err as { detail: unknown }).detail) : fallback,
  getClaudeRuntime: () => getRuntimeBehavior(),
  listRuntimeTargets: () => listTargetsBehavior(),
  listConnections: () => listConnectionsBehavior(),
  setClaudeRuntime: () => setRuntimeBehavior(),
  verifyProviderCredential: () => verifyBehavior(),
  createRuntimeTarget: vi.fn(),
}))

import { ClaudeRuntimeSection } from './ClaudeRuntimeSection'

const activeTarget: StubRuntimeTarget = {
  id: 'target-1',
  name: 'my-target',
  provider: 'gcp-cloud-run',
  connectionId: 'conn-1',
  gcpProjectId: 'customer-proj',
  region: 'us-central1',
  jobName: 'conductor-my-target',
  image: 'img:1',
  status: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

let mockCanMutate = true

describe('ClaudeRuntimeSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanMutate = true
    getRuntimeBehavior = () =>
      Promise.resolve({ source: 'builtin', runtimeTargetId: null, runtimeTarget: null, builtinConfigured: true })
    listTargetsBehavior = () => Promise.resolve([])
    listConnectionsBehavior = () => Promise.resolve([{ id: 'conn-1', status: 'ACTIVE', label: 'prod-sa' }])
    setRuntimeBehavior = () =>
      Promise.resolve({ source: 'project-target', runtimeTargetId: 'target-1', runtimeTarget: activeTarget, builtinConfigured: true })
    verifyBehavior = () =>
      Promise.resolve({ provider: 'claude-code', status: 'verified', checkedAt: '2026-01-01T00:00:00Z', checks: [] })
  })

  it('shows the builtin runtime as Ready when configured', async () => {
    render(<ClaudeRuntimeSection projectId="proj-1" />)

    expect(await screen.findByText('Built-in Conductor runtime')).toBeInTheDocument()
    expect(screen.getByText('Ready')).toBeInTheDocument()
  })

  it('shows Not configured and an actionable note when the builtin has no fallback', async () => {
    getRuntimeBehavior = () =>
      Promise.resolve({ source: 'builtin', runtimeTargetId: null, runtimeTarget: null, builtinConfigured: false })

    render(<ClaudeRuntimeSection projectId="proj-1" />)

    expect(await screen.findByText('Not configured')).toBeInTheDocument()
    expect(screen.getByText(/link a runtime target below/i)).toBeInTheDocument()
  })

  it('shows the designated target name and status when one is set', async () => {
    getRuntimeBehavior = () =>
      Promise.resolve({ source: 'project-target', runtimeTargetId: 'target-1', runtimeTarget: activeTarget, builtinConfigured: true })
    listTargetsBehavior = () => Promise.resolve([activeTarget])

    render(<ClaudeRuntimeSection projectId="proj-1" />)

    // "my-target" appears both in the summary and as a <select> option — assert at least one match
    // rather than pinning to a single node.
    await waitFor(() => {
      expect(screen.getAllByText('my-target').length).toBeGreaterThan(0)
    })
    expect(screen.getByText('Active')).toBeInTheDocument()
  })

  it('shows a connect-gcp hint when there is no ACTIVE gcp connection', async () => {
    listConnectionsBehavior = () => Promise.resolve([{ id: 'conn-1', status: 'NEEDS_SETUP', label: 'broken-sa' }])

    render(<ClaudeRuntimeSection projectId="proj-1" />)

    expect(await screen.findByText(/connect google cloud to link a runtime target/i)).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /set up/i })
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/integrations/gcp')
  })

  it('changing the designation calls setClaudeRuntime then auto-verifies and surfaces the report', async () => {
    listTargetsBehavior = () => Promise.resolve([activeTarget])
    verifyBehavior = () =>
      Promise.resolve({
        provider: 'claude-code',
        status: 'verified',
        checkedAt: '2026-01-01T00:00:00Z',
        checks: [{ name: 'runtime-config', status: 'pass', message: 'ok' }],
      })
    const user = userEvent.setup()
    render(<ClaudeRuntimeSection projectId="proj-1" />)

    const select = await screen.findByLabelText('Claude runtime target')
    await user.selectOptions(select, 'target-1')

    await waitFor(() => {
      expect(screen.getByText(/re-verification: verified/i)).toBeInTheDocument()
    })
  })

  it('hides the select and shows a read-only notice when the viewer cannot mutate', async () => {
    mockCanMutate = false

    render(<ClaudeRuntimeSection projectId="proj-1" />)

    expect(await screen.findByText('Built-in Conductor runtime')).toBeInTheDocument()
    expect(screen.queryByLabelText('Claude runtime target')).not.toBeInTheDocument()
    expect(screen.getByText(/only admins and creators can change the runtime target/i)).toBeInTheDocument()
  })
})
