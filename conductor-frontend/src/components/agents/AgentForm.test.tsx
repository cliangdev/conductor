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
let listAgentProvidersBehavior: () => Promise<{ id: string; defaultModel?: string | null }[]> = () =>
  Promise.resolve([{ id: 'claude', defaultModel: 'claude-opus-4-8' }])
let listAgentToolsBehavior: () => Promise<unknown[]> = () => Promise.resolve([])

vi.mock('@/lib/api', () => ({
  listAgentProviders: () => listAgentProvidersBehavior(),
  listAgentTools: () => listAgentToolsBehavior(),
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
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('AgentForm avatar payload', () => {
  beforeEach(() => {
    listAgentProvidersBehavior = () => Promise.resolve([{ id: 'claude', defaultModel: 'claude-opus-4-8' }])
    listAgentToolsBehavior = () => Promise.resolve([])
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
