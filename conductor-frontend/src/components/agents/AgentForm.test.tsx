import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('next/navigation', () => ({
  useRouter: () => mockRouter,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => mockAuthContext,
}))

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory. Not exercised here, but kept consistent with the
// repo convention for lib/api mocks.
let listAgentProvidersBehavior: () => Promise<{ id: string; defaultModel?: string | null; defaultModelIsLive: boolean }[]> = () =>
  Promise.resolve([{ id: 'claude', defaultModel: 'claude-opus-4-8', defaultModelIsLive: false }])
let listAgentToolsBehavior: () => Promise<unknown[]> = () => Promise.resolve([])
let listProviderModelsBehavior: () => Promise<{ models: { id: string; latest: boolean }[] }> = () =>
  Promise.resolve({ models: [] })

vi.mock('@/lib/api', () => ({
  listAgentProviders: () => listAgentProvidersBehavior(),
  listAgentTools: () => listAgentToolsBehavior(),
  listProviderModels: () => listProviderModelsBehavior(),
}))

import { AgentForm } from './AgentForm'
import { CURATED_EMOJIS } from './AgentAvatarPicker'
import { AVATAR_COLOR_TOKENS } from './AgentAvatar'
import type { Agent } from '@/lib/api'

const mockRouter = { push: vi.fn(), back: vi.fn() }
const mockAuthContext = { user: null, accessToken: 'test-token', loading: false }

function baseAgent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: 'agent-1',
    projectId: 'proj-1',
    name: 'Marketer',
    slug: 'marketer',
    provider: 'claude',
    toolIds: [],
    state: 'ACTIVE',
    avatarEmoji: '🦉',
    avatarColor: 'teal',
    isDefault: false,
    addressable: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('AgentForm avatar payload', () => {
  beforeEach(() => {
    listAgentProvidersBehavior = () =>
      Promise.resolve([{ id: 'claude', defaultModel: 'claude-opus-4-8', defaultModelIsLive: false }])
    listAgentToolsBehavior = () => Promise.resolve([])
    listProviderModelsBehavior = () => Promise.resolve({ models: [] })
  })

  it('create mode: submits an avatar pair drawn from the known emoji/color sets', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(<AgentForm projectId="proj-1" submitLabel="Create Agent" saving={false} error={null} onSubmit={onSubmit} />)

    await user.type(screen.getByLabelText('Name'), 'New Agent')
    await waitFor(() => expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('claude'))
    await user.click(screen.getByRole('button', { name: 'Create Agent' }))

    expect(onSubmit).toHaveBeenCalledTimes(1)
    const body = onSubmit.mock.calls[0][0]
    expect(CURATED_EMOJIS).toContain(body.avatarEmoji)
    expect(AVATAR_COLOR_TOKENS).toContain(body.avatarColor)
  })

  it('edit mode: preserves the existing agent avatar when untouched', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(
      <AgentForm
        projectId="proj-1"
        initial={baseAgent()}
        submitLabel="Save"
        saving={false}
        error={null}
        onSubmit={onSubmit}
      />
    )

    await waitFor(() => expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('claude'))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ avatarEmoji: '🦉', avatarColor: 'teal' }))
  })

  it('edit mode: a picker change is reflected in the submitted payload', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(
      <AgentForm
        projectId="proj-1"
        initial={baseAgent()}
        submitLabel="Save"
        saving={false}
        error={null}
        onSubmit={onSubmit}
      />
    )

    await waitFor(() => expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('claude'))
    await user.click(screen.getByRole('button', { name: 'Use violet' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ avatarEmoji: '🦉', avatarColor: 'violet' }))
  })
})

describe('AgentForm addressable toggle', () => {
  beforeEach(() => {
    listAgentProvidersBehavior = () =>
      Promise.resolve([{ id: 'claude', defaultModel: 'claude-opus-4-8', defaultModelIsLive: false }])
    listAgentToolsBehavior = () => Promise.resolve([])
    listProviderModelsBehavior = () => Promise.resolve({ models: [] })
  })

  it('create mode: defaults off and submits config.addressable=false untouched', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(<AgentForm projectId="proj-1" submitLabel="Create Agent" saving={false} error={null} onSubmit={onSubmit} />)

    await user.type(screen.getByLabelText('Name'), 'New Agent')
    await waitFor(() => expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('claude'))
    expect(screen.getByRole('switch', { name: 'Addressable' })).toHaveAttribute('aria-checked', 'false')
    await user.click(screen.getByRole('button', { name: 'Create Agent' }))

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ config: expect.objectContaining({ addressable: false }) }),
    )
  })

  it('create mode: toggling on submits config.addressable=true', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(<AgentForm projectId="proj-1" submitLabel="Create Agent" saving={false} error={null} onSubmit={onSubmit} />)

    await user.type(screen.getByLabelText('Name'), 'New Agent')
    await waitFor(() => expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('claude'))
    await user.click(screen.getByRole('switch', { name: 'Addressable' }))
    await user.click(screen.getByRole('button', { name: 'Create Agent' }))

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ config: expect.objectContaining({ addressable: true }) }),
    )
  })

  it('edit mode: an already-addressable agent renders the toggle on and preserves it untouched', async () => {
    const user = userEvent.setup()
    const onSubmit = vi.fn()
    render(
      <AgentForm
        projectId="proj-1"
        initial={baseAgent({ addressable: true })}
        submitLabel="Save"
        saving={false}
        error={null}
        onSubmit={onSubmit}
      />
    )

    await waitFor(() => expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('claude'))
    expect(screen.getByRole('switch', { name: 'Addressable' })).toHaveAttribute('aria-checked', 'true')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({ config: expect.objectContaining({ addressable: true }) }),
    )
  })
})

describe('AgentForm model picker', () => {
  beforeEach(() => {
    listAgentProvidersBehavior = () =>
      Promise.resolve([{ id: 'claude', defaultModel: 'claude-opus-4-8', defaultModelIsLive: false }])
    listAgentToolsBehavior = () => Promise.resolve([])
    listProviderModelsBehavior = () => Promise.resolve({ models: [] })
  })

  it('populates a datalist of model ids from listProviderModels', async () => {
    listProviderModelsBehavior = () =>
      Promise.resolve({ models: [{ id: 'claude-opus-5', latest: true }, { id: 'claude-sonnet-5', latest: false }] })
    render(<AgentForm projectId="proj-1" submitLabel="Create Agent" saving={false} error={null} onSubmit={vi.fn()} />)

    const modelInput = await screen.findByLabelText('Model')
    await waitFor(() => {
      expect(screen.getByText('claude-opus-5 (latest)')).toBeInTheDocument()
      expect(screen.getByText('claude-sonnet-5')).toBeInTheDocument()
    })
    expect(modelInput).toHaveAttribute('list', 'agent-model-options')
  })

  it('leaves the plain free-text field when the model list is empty', async () => {
    listProviderModelsBehavior = () => Promise.resolve({ models: [] })
    render(<AgentForm projectId="proj-1" submitLabel="Create Agent" saving={false} error={null} onSubmit={vi.fn()} />)

    const modelInput = await screen.findByLabelText('Model')
    await waitFor(() => expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('claude'))
    expect(modelInput).not.toHaveAttribute('list')
  })

  it('does not throw and falls back to free text when the model request rejects', async () => {
    // Plain per-test stub, not vi.fn() — see reference_vitest_rejected_promise_mock memory.
    listProviderModelsBehavior = () => Promise.reject(new Error('boom'))
    render(<AgentForm projectId="proj-1" submitLabel="Create Agent" saving={false} error={null} onSubmit={vi.fn()} />)

    const modelInput = await screen.findByLabelText('Model')
    await waitFor(() => expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('claude'))
    expect(modelInput).not.toHaveAttribute('list')
  })
})

// Blank-model copy must be driven by defaultModelIsLive, never by whether discovery returned
// suggestions — see the finding this covers: the two are independent (a live provider can return
// zero models; a pinned provider can still populate a datalist).
describe('AgentForm blank-model copy', () => {
  beforeEach(() => {
    listAgentToolsBehavior = () => Promise.resolve([])
  })

  it('a pinned-default provider (defaultModelIsLive: false) gets the fixed-default copy', async () => {
    listAgentProvidersBehavior = () =>
      Promise.resolve([{ id: 'claude', defaultModel: 'claude-opus-4-8', defaultModelIsLive: false }])
    listProviderModelsBehavior = () =>
      Promise.resolve({ models: [{ id: 'claude-opus-5', latest: true }] })
    render(<AgentForm projectId="proj-1" submitLabel="Create Agent" saving={false} error={null} onSubmit={vi.fn()} />)

    const modelInput = await screen.findByLabelText('Model')
    await waitFor(() => expect(modelInput).toHaveAttribute('placeholder', 'claude-opus-4-8 (default)'))
    expect(screen.getByText('Leave blank to use the provider default.')).toBeInTheDocument()
  })

  it('a live-default provider (defaultModelIsLive: true) gets the latest-model copy, not a named default', async () => {
    listAgentProvidersBehavior = () =>
      Promise.resolve([{ id: 'openai', defaultModel: 'gpt-5.4', defaultModelIsLive: true }])
    listProviderModelsBehavior = () => Promise.resolve({ models: [] })
    render(<AgentForm projectId="proj-1" submitLabel="Create Agent" saving={false} error={null} onSubmit={vi.fn()} />)

    const modelInput = await screen.findByLabelText('Model')
    await waitFor(() => expect(modelInput).toHaveAttribute('placeholder', 'Latest supported model'))
    expect(
      screen.getByText("Leave blank to use the provider's latest supported model."),
    ).toBeInTheDocument()
    // Must never present gpt-5.4 as "the default" — it's only a last-resort fallback for a live provider.
    expect(screen.queryByText('gpt-5.4 (default)')).not.toBeInTheDocument()
  })
})
