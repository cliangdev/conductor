import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

// vitest 4 flags a vi.fn() mock whose implementation returns a rejected promise as an unhandled
// rejection even when the component awaits/catches it — drive rejections through a plain,
// per-test behavior variable instead (see reference_vitest_rejected_promise_mock memory).
let statusesBehavior: () => Promise<{ provider: string; configured: boolean }[]> = () =>
  Promise.resolve([
    { provider: 'claude-code', configured: false },
    { provider: 'claude', configured: false },
  ])

vi.mock('@/lib/api', () => ({
  listProviderCredentialStatuses: () => statusesBehavior(),
}))

import { ClaudeConnectionHint } from './ClaudeConnectionHint'

describe('ClaudeConnectionHint', () => {
  beforeEach(() => {
    statusesBehavior = () =>
      Promise.resolve([
        { provider: 'claude-code', configured: false },
        { provider: 'claude', configured: false },
      ])
  })

  it('renders the connect hint with a settings link when neither method is configured', async () => {
    render(<ClaudeConnectionHint projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Connect Claude to power the librarian.')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /set up/i })
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/settings/providers')
  })

  it('renders the subscription note when only the API key is configured', async () => {
    statusesBehavior = () =>
      Promise.resolve([
        { provider: 'claude-code', configured: false },
        { provider: 'claude', configured: true },
      ])

    render(<ClaudeConnectionHint projectId="proj-1" token="tok" />)

    expect(
      await screen.findByText(
        'Claude is connected via API key. The bootstrap workflow additionally needs a Claude Code subscription.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /set up/i })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/settings/providers',
    )
  })

  it('renders nothing once claude-code is configured', async () => {
    statusesBehavior = () =>
      Promise.resolve([
        { provider: 'claude-code', configured: true },
        { provider: 'claude', configured: false },
      ])

    const { container } = render(<ClaudeConnectionHint projectId="proj-1" token="tok" />)

    await waitFor(() => {
      expect(container).toBeEmptyDOMElement()
    })
  })

  it('treats a failed status check as not-connected rather than crashing', async () => {
    statusesBehavior = () => Promise.reject(new Error('network down'))

    render(<ClaudeConnectionHint projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Connect Claude to power the librarian.')).toBeInTheDocument()
  })

  it('shows a loading skeleton before the status check resolves', () => {
    statusesBehavior = () => new Promise(() => {}) // never resolves during this test

    const { container } = render(<ClaudeConnectionHint projectId="proj-1" token="tok" />)

    expect(container.querySelector('.animate-pulse')).toBeInTheDocument()
    expect(screen.queryByText('Connect Claude to power the librarian.')).not.toBeInTheDocument()
  })
})
