import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: (cap: string) => (cap === 'integration.manage' ? mockCanMutate : false),
}))

const showToast = vi.fn()
vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast }),
}))

vi.mock('@/lib/api', () => ({
  listRuntimeTargets: vi.fn(),
  createRuntimeTarget: vi.fn(),
  updateRuntimeTarget: vi.fn(),
  deleteRuntimeTarget: vi.fn(),
  provisionRuntimeTarget: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => {
    const detail = (err as { detail?: unknown })?.detail
    return typeof detail === 'string' && detail.trim() ? detail : fallback
  },
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

// Flatten the dropdown so RowActionsMenu items are always visible/clickable in jsdom
// (same approach as Navbar.test.tsx — the real Radix/base-ui popup is portal-based).
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuItem: ({ children, onSelect }: { children: React.ReactNode; onSelect?: () => void }) => (
    <button type="button" onClick={onSelect}>{children}</button>
  ),
}))

import * as api from '@/lib/api'
import RuntimeTargetsPanel from './RuntimeTargetsPanel'

let mockCanMutate = true

const activeTarget = {
  id: 't-active',
  name: 'active-target',
  provider: 'gcp-cloud-run' as const,
  connectionId: 'conn-1',
  gcpProjectId: 'my-project',
  region: 'us-central1',
  jobName: 'conductor-active-target',
  image: 'us-central1-docker.pkg.dev/my-project/repo/img:tag',
  status: 'ACTIVE' as const,
  errorMessage: null,
  warnings: null,
  resolvedImage: 'us-central1-docker.pkg.dev/my-project/repo/img@sha256:abcdef0123456789',
  lastProvisionedAt: new Date(Date.now() - 5 * 60_000).toISOString(),
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const provisioningTarget = {
  ...activeTarget,
  id: 't-provisioning',
  name: 'provisioning-target',
  status: 'PROVISIONING' as const,
}

const errorTarget = {
  ...activeTarget,
  id: 't-error',
  name: 'error-target',
  status: 'ERROR' as const,
  errorMessage: 'Image not found in Artifact Registry',
}

const activeConnection = { id: 'conn-1', status: 'ACTIVE' as const, label: 'prod-sa' }
const needsSetupConnection = { id: 'conn-2', status: 'NEEDS_SETUP' as const, label: 'broken-sa' }

describe('RuntimeTargetsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanMutate = true
  })

  it('renders a list with all three statuses', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([activeTarget, provisioningTarget, errorTarget])
    render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)

    expect(await screen.findByText('active-target')).toBeInTheDocument()
    expect(screen.getByText('provisioning-target')).toBeInTheDocument()
    expect(screen.getByText('error-target')).toBeInTheDocument()

    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByText('Provisioning')).toBeInTheDocument()
    expect(screen.getByText(/Image not found in Artifact Registry/)).toBeInTheDocument()

    // ACTIVE-only "ground truth" line — the resolved digest GCP actually pinned, not just the
    // configured tag, plus when that resolution last happened.
    expect(screen.getByText(/Synced 5m ago · sha256:abcdef01…/)).toBeInTheDocument()
  })

  describe('rendering line', () => {
    const line = (id: string) => screen.getByTestId(`render-line-${id}`)

    it('NONE: shows "Not set up for rendering" with a set-up action', async () => {
      vi.mocked(api.listRuntimeTargets).mockResolvedValue([{ ...activeTarget, render: { status: 'NONE' } }])
      render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)
      await screen.findByText('active-target')
      expect(within(line('t-active')).getByText('Not set up for rendering')).toBeInTheDocument()
      expect(within(line('t-active')).getByRole('button', { name: 'Set up rendering' })).toBeInTheDocument()
    })

    it('missing render field is treated as NONE', async () => {
      vi.mocked(api.listRuntimeTargets).mockResolvedValue([activeTarget])
      render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)
      await screen.findByText('active-target')
      expect(within(line('t-active')).getByText('Not set up for rendering')).toBeInTheDocument()
    })

    it('NONE: hides the set-up action without integration.manage', async () => {
      mockCanMutate = false
      vi.mocked(api.listRuntimeTargets).mockResolvedValue([{ ...activeTarget, render: { status: 'NONE' } }])
      render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)
      await screen.findByText('active-target')
      expect(within(line('t-active')).queryByRole('button')).toBeNull()
    })

    it('READY: shows "Renders creatives", the resolved digest and any warnings', async () => {
      vi.mocked(api.listRuntimeTargets).mockResolvedValue([
        {
          ...activeTarget,
          renderImage: 'us-docker.pkg.dev/p/r/conductor-render:1',
          render: {
            status: 'READY',
            resolvedImage: 'us-docker.pkg.dev/p/r/conductor-render@sha256:1234567890abcdef',
            warnings: ['Image has no protocol label.'],
          },
        },
      ])
      render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)
      await screen.findByText('active-target')
      expect(within(line('t-active')).getByText('Renders creatives')).toBeInTheDocument()
      expect(within(line('t-active')).getByText('sha256:12345678…')).toBeInTheDocument()
      expect(within(line('t-active')).getByText('Image has no protocol label.')).toBeInTheDocument()
    })

    it('ERROR: shows the error in destructive text and points at Sync to latest image', async () => {
      vi.mocked(api.listRuntimeTargets).mockResolvedValue([
        { ...activeTarget, render: { status: 'ERROR', error: 'Image not found' } },
      ])
      render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)
      await screen.findByText('active-target')
      expect(within(line('t-active')).getByText('Image not found')).toHaveClass('text-destructive')
      // the target itself stays Active
      expect(screen.getByText('Active')).toBeInTheDocument()
      expect(within(line('t-active')).getByText(/Sync to latest image/)).toBeInTheDocument()
    })

    it('ERROR: Sync to latest image re-provisions the target', async () => {
      const errored = { ...activeTarget, render: { status: 'ERROR' as const, error: 'Image not found' } }
      vi.mocked(api.listRuntimeTargets).mockResolvedValue([errored])
      vi.mocked(api.provisionRuntimeTarget).mockResolvedValue({
        ...activeTarget,
        render: { status: 'READY', resolvedImage: 'x@sha256:abcdefabcdefabcdef' },
      })
      render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)
      await screen.findByText('active-target')
      fireEvent.click(screen.getByRole('button', { name: 'Sync to latest image' }))
      expect(await within(line('t-active')).findByText('Renders creatives')).toBeInTheDocument()
      expect(api.provisionRuntimeTarget).toHaveBeenCalledWith('proj-1', 't-active', 'test-token')
    })

    it('rejects a non Artifact Registry image client-side with the backend message', async () => {
      vi.mocked(api.listRuntimeTargets).mockResolvedValue([{ ...activeTarget, render: { status: 'NONE' } }])
      render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)
      await screen.findByText('active-target')
      fireEvent.click(screen.getByRole('button', { name: 'Set up rendering' }))
      fireEvent.change(screen.getByLabelText('Render image'), { target: { value: 'ghcr.io/cliangdev/conductor-render:1' } })
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      expect(await screen.findByRole('alert')).toHaveTextContent(
        'Not an Artifact Registry image reference (expected a *-docker.pkg.dev host): ghcr.io/cliangdev/conductor-render:1',
      )
      expect(api.updateRuntimeTarget).not.toHaveBeenCalled()
    })

    it('saves a valid render image with { renderImage } only and shows the returned state', async () => {
      vi.mocked(api.listRuntimeTargets).mockResolvedValue([{ ...activeTarget, render: { status: 'NONE' } }])
      vi.mocked(api.updateRuntimeTarget).mockResolvedValue({
        ...activeTarget,
        renderImage: 'us-docker.pkg.dev/p/r/conductor-render:1',
        render: { status: 'READY', resolvedImage: 'us-docker.pkg.dev/p/r/conductor-render@sha256:abcdefabcdefabcd' },
      })
      render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)
      await screen.findByText('active-target')
      fireEvent.click(screen.getByRole('button', { name: 'Set up rendering' }))
      fireEvent.change(screen.getByLabelText('Render image'), {
        target: { value: ' us-docker.pkg.dev/p/r/conductor-render:1 ' },
      })
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      await waitFor(() =>
        expect(api.updateRuntimeTarget).toHaveBeenCalledWith(
          'proj-1',
          't-active',
          { renderImage: 'us-docker.pkg.dev/p/r/conductor-render:1' },
          'test-token',
        ),
      )
      expect(await within(line('t-active')).findByText('Renders creatives')).toBeInTheDocument()
    })

    it('shows a backend error from the update inside the modal', async () => {
      vi.mocked(api.listRuntimeTargets).mockResolvedValue([{ ...activeTarget, render: { status: 'NONE' } }])
      vi.mocked(api.updateRuntimeTarget).mockRejectedValue({ detail: 'Update refused' })
      render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)
      await screen.findByText('active-target')
      fireEvent.click(screen.getByRole('button', { name: 'Set up rendering' }))
      fireEvent.change(screen.getByLabelText('Render image'), { target: { value: 'us-docker.pkg.dev/p/r/i:1' } })
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('Update refused')
    })
  })

  it('shows a fallback sync label for an ACTIVE target provisioned before this was tracked', async () => {
    const untracked = { ...activeTarget, id: 't-untracked', name: 'untracked-target', resolvedImage: null, lastProvisionedAt: null }
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([untracked])
    render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)

    expect(await screen.findByText('Synced before this was tracked')).toBeInTheDocument()
  })

  it('shows connect hint when no ACTIVE connection', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([])
    render(<RuntimeTargetsPanel projectId="proj-1" connections={[needsSetupConnection]} />)

    expect(await screen.findByText(/connect an active google cloud service account above/i)).toBeInTheDocument()
  })

  it('shows an "Add runtime" CTA in the empty state when an ACTIVE gcp connection exists', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([])
    render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)

    expect(await screen.findByText(/no runtime targets yet/i)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /add runtime/i }).length).toBeGreaterThan(0)
  })

  it('create flow lists only ACTIVE gcp connections and posts the right payload shape', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([])
    vi.mocked(api.createRuntimeTarget).mockResolvedValue({ ...activeTarget, name: 'my-target' })
    render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection, needsSetupConnection]} />)

    fireEvent.click((await screen.findAllByRole('button', { name: /add runtime/i }))[0])
    const modal = await screen.findByTestId('modal')

    const connectionSelect = within(modal).getByLabelText(/connection/i) as HTMLSelectElement
    const optionValues = Array.from(connectionSelect.options).map((o) => o.value).filter(Boolean)
    expect(optionValues).toEqual(['conn-1'])

    fireEvent.change(within(modal).getByLabelText(/^name$/i), { target: { value: 'my-target' } })
    fireEvent.change(within(modal).getByLabelText(/gcp project id/i), { target: { value: 'my-project' } })
    fireEvent.change(within(modal).getByLabelText(/region/i), { target: { value: 'us-central1' } })
    fireEvent.change(within(modal).getByLabelText(/^image$/i), {
      target: { value: 'us-central1-docker.pkg.dev/my-project/repo/img:tag' },
    })
    fireEvent.click(within(modal).getByRole('button', { name: /^create$/i }))

    await waitFor(() => {
      expect(api.createRuntimeTarget).toHaveBeenCalledWith(
        'proj-1',
        {
          name: 'my-target',
          provider: 'gcp-cloud-run',
          connectionId: 'conn-1',
          gcpProjectId: 'my-project',
          region: 'us-central1',
          image: 'us-central1-docker.pkg.dev/my-project/repo/img:tag',
        },
        'test-token',
      )
    })
  })

  it('blocks submit on an invalid slug without calling the API', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([])
    render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)

    fireEvent.click((await screen.findAllByRole('button', { name: /add runtime/i }))[0])
    const modal = await screen.findByTestId('modal')

    fireEvent.change(within(modal).getByLabelText(/^name$/i), { target: { value: 'Not A Valid Slug!' } })
    fireEvent.change(within(modal).getByLabelText(/gcp project id/i), { target: { value: 'my-project' } })
    fireEvent.change(within(modal).getByLabelText(/region/i), { target: { value: 'us-central1' } })
    fireEvent.change(within(modal).getByLabelText(/^image$/i), { target: { value: 'img:tag' } })

    const createButton = within(modal).getByRole('button', { name: /^create$/i })
    expect(createButton).toBeDisabled()
    fireEvent.click(createButton)

    expect(api.createRuntimeTarget).not.toHaveBeenCalled()
  })

  it('renders the RFC-7807 detail message when create fails', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([])
    // A plain per-test throwing stub — mockRejectedValue trips the unhandled-rejection flag in this repo.
    vi.mocked(api.createRuntimeTarget).mockImplementation(async () => {
      throw Object.assign(new Error('conflict'), {
        status: 409,
        detail: 'A runtime target named "my-target" already exists.',
      })
    })
    render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)

    fireEvent.click((await screen.findAllByRole('button', { name: /add runtime/i }))[0])
    const modal = await screen.findByTestId('modal')

    fireEvent.change(within(modal).getByLabelText(/^name$/i), { target: { value: 'my-target' } })
    fireEvent.change(within(modal).getByLabelText(/gcp project id/i), { target: { value: 'my-project' } })
    fireEvent.change(within(modal).getByLabelText(/region/i), { target: { value: 'us-central1' } })
    fireEvent.change(within(modal).getByLabelText(/^image$/i), { target: { value: 'img:tag' } })
    fireEvent.click(within(modal).getByRole('button', { name: /^create$/i }))

    expect(await screen.findByText(/already exists/i)).toBeInTheDocument()
  })

  it('delete confirm calls deleteRuntimeTarget', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([activeTarget])
    vi.mocked(api.deleteRuntimeTarget).mockResolvedValue(undefined)
    render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)

    await screen.findByText('active-target')
    fireEvent.click(screen.getByRole('button', { name: /delete/i }))

    const modal = await screen.findByTestId('modal')
    fireEvent.click(within(modal).getByRole('button', { name: /^delete$/i }))

    await waitFor(() => {
      expect(api.deleteRuntimeTarget).toHaveBeenCalledWith('proj-1', 't-active', 'test-token')
    })
  })

  it('hides mutation controls in read-only mode', async () => {
    mockCanMutate = false
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([activeTarget])
    render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)

    await screen.findByText('active-target')
    expect(screen.queryByRole('button', { name: /add runtime/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /more actions/i })).not.toBeInTheDocument()
  })

  it('re-fetches targets when the connections prop changes', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([activeTarget])
    const { rerender } = render(<RuntimeTargetsPanel projectId="proj-1" connections={[activeConnection]} />)

    await screen.findByText('active-target')
    expect(api.listRuntimeTargets).toHaveBeenCalledTimes(1)

    rerender(<RuntimeTargetsPanel projectId="proj-1" connections={[]} />)

    await waitFor(() => {
      expect(api.listRuntimeTargets).toHaveBeenCalledTimes(2)
    })
  })
})
