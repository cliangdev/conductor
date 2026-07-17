import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory.
let credentialStatusBehavior: (
  projectId: string,
  provider: string,
) => Promise<{ provider: string; configured: boolean }> = () => Promise.resolve({ provider: 'claude', configured: false })
let listProjectApiKeysBehavior: () => Promise<{ id: string; name: string; createdAt: string }[]> = () =>
  Promise.resolve([])

vi.mock('@/lib/api', () => ({
  getProviderCredentialStatus: (projectId: string, provider: string) => credentialStatusBehavior(projectId, provider),
  listProjectApiKeys: () => listProjectApiKeysBehavior(),
}))

import { KnowledgeSetupChecklist } from './KnowledgeSetupChecklist'

describe('KnowledgeSetupChecklist', () => {
  beforeEach(() => {
    credentialStatusBehavior = () => Promise.resolve({ provider: 'claude', configured: false })
    listProjectApiKeysBehavior = () => Promise.resolve([])
  })

  it('renders both rows unchecked when neither prereq is satisfied', async () => {
    render(<KnowledgeSetupChecklist projectId="proj-1" token="tok" onEnable={vi.fn()} enabling={false} />)

    expect(await screen.findByText('Claude credential')).toBeInTheDocument()
    expect(screen.getByText('Project API key')).toBeInTheDocument()
    // Unsatisfied rows render their hint links.
    await waitFor(() => {
      expect(screen.getByText('Add in Providers')).toBeInTheDocument()
      expect(screen.getByText('Add in Settings')).toBeInTheDocument()
    })
  })

  it('hides the credential hint once a claude-code or claude credential is configured', async () => {
    credentialStatusBehavior = (_projectId, provider) =>
      Promise.resolve({ provider, configured: provider === 'claude-code' })

    render(<KnowledgeSetupChecklist projectId="proj-1" token="tok" onEnable={vi.fn()} enabling={false} />)

    await waitFor(() => {
      expect(screen.queryByText('Add in Providers')).not.toBeInTheDocument()
    })
    // API key row is still unsatisfied.
    expect(screen.getByText('Add in Settings')).toBeInTheDocument()
  })

  it('hides the api key hint once at least one project API key exists', async () => {
    listProjectApiKeysBehavior = () => Promise.resolve([{ id: 'key-1', name: 'CI', createdAt: '2026-01-01T00:00:00Z' }])

    render(<KnowledgeSetupChecklist projectId="proj-1" token="tok" onEnable={vi.fn()} enabling={false} />)

    await waitFor(() => {
      expect(screen.queryByText('Add in Settings')).not.toBeInTheDocument()
    })
  })

  it('calls onEnable when the Enable Knowledge button is clicked, regardless of prereq state', async () => {
    const onEnable = vi.fn()
    const user = userEvent.setup()
    render(<KnowledgeSetupChecklist projectId="proj-1" token="tok" onEnable={onEnable} enabling={false} />)

    const button = await screen.findByRole('button', { name: 'Enable Knowledge' })
    expect(button).not.toBeDisabled()
    await user.click(button)

    expect(onEnable).toHaveBeenCalledTimes(1)
  })

  it('disables the button and shows a busy label while enabling', async () => {
    render(<KnowledgeSetupChecklist projectId="proj-1" token="tok" onEnable={vi.fn()} enabling={true} />)

    const button = await screen.findByRole('button', { name: 'Enabling…' })
    expect(button).toBeDisabled()
  })

  it('treats a failed credential/api-key check as unsatisfied rather than crashing', async () => {
    credentialStatusBehavior = () => Promise.reject(new Error('network down'))
    listProjectApiKeysBehavior = () => Promise.reject(new Error('network down'))

    render(<KnowledgeSetupChecklist projectId="proj-1" token="tok" onEnable={vi.fn()} enabling={false} />)

    await waitFor(() => {
      expect(screen.getByText('Add in Providers')).toBeInTheDocument()
      expect(screen.getByText('Add in Settings')).toBeInTheDocument()
    })
  })
})
