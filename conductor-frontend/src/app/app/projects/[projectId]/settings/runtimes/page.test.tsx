import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({
    can: (cap: string) => (cap === 'integration.manage' ? mockCanMutate : false),
  }),
}))

vi.mock('@/lib/api', () => ({
  listConnections: vi.fn(),
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
import RuntimesPage from './page'

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

describe('RuntimesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanMutate = true
    vi.mocked(api.listConnections).mockResolvedValue([activeConnection, needsSetupConnection])
  })

  it('renders a list with all three statuses', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([activeTarget, provisioningTarget, errorTarget])
    render(<RuntimesPage />)

    expect(await screen.findByText('active-target')).toBeInTheDocument()
    expect(screen.getByText('provisioning-target')).toBeInTheDocument()
    expect(screen.getByText('error-target')).toBeInTheDocument()

    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByText('Provisioning')).toBeInTheDocument()
    expect(screen.getByText(/Image not found in Artifact Registry/)).toBeInTheDocument()
  })

  it('shows a "connect Google Cloud first" empty state when there is no ACTIVE gcp connection', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([])
    vi.mocked(api.listConnections).mockResolvedValue([needsSetupConnection])
    render(<RuntimesPage />)

    const link = await screen.findByRole('link', { name: /go to integrations/i })
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/integrations')
  })

  it('shows an "Add runtime" CTA in the empty state when an ACTIVE gcp connection exists', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([])
    render(<RuntimesPage />)

    expect(await screen.findByText(/no runtime targets yet/i)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /add runtime/i }).length).toBeGreaterThan(0)
  })

  it('create flow lists only ACTIVE gcp connections and posts the right payload shape', async () => {
    vi.mocked(api.listRuntimeTargets).mockResolvedValue([])
    vi.mocked(api.createRuntimeTarget).mockResolvedValue({ ...activeTarget, name: 'my-target' })
    render(<RuntimesPage />)

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
    render(<RuntimesPage />)

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
    render(<RuntimesPage />)

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
    render(<RuntimesPage />)

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
    render(<RuntimesPage />)

    await screen.findByText('active-target')
    expect(screen.queryByRole('button', { name: /add runtime/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /more actions/i })).not.toBeInTheDocument()
  })
})
