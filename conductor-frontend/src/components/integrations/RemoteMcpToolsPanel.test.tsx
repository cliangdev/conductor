import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'tok' }),
}))

vi.mock('./ConnectorCatalogContext', () => ({
  useConnectorCatalogItem: () => ({
    item: { connections: [{ id: 'c1', label: 'DeepWiki', status: 'ACTIVE' }] },
    refetch: async () => {},
  }),
}))

const calls: boolean[] = []
let behavior: (refresh: boolean) => Promise<unknown> = () => Promise.resolve({ toolCount: 0, tools: [] })

vi.mock('@/lib/api', () => ({
  listRemoteMcpTools: (_p: string, _c: string, _t: string, refresh: boolean) => {
    calls.push(refresh)
    return behavior(refresh)
  },
  apiErrorMessage: (_e: unknown, fallback: string) => fallback,
}))

import RemoteMcpToolsPanel from './RemoteMcpToolsPanel'

describe('RemoteMcpToolsPanel', () => {
  beforeEach(() => {
    calls.length = 0
  })

  it('lists tool names and descriptions, and Refresh bypasses the cache', async () => {
    behavior = () =>
      Promise.resolve({
        toolCount: 2,
        tools: [
          { name: 'ask_question', description: 'Ask a question' },
          { name: 'read_wiki', description: null },
        ],
      })
    const user = userEvent.setup()
    render(<RemoteMcpToolsPanel projectId="p1" />)

    expect(await screen.findByText('ask_question')).toBeInTheDocument()
    expect(screen.getByText('Ask a question')).toBeInTheDocument()
    expect(screen.getByText('2 tools')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(calls).toEqual([false, true]))
  })

  it('shows the plain error when the server cannot be listed', async () => {
    behavior = () => Promise.resolve({ toolCount: 0, tools: [], error: 'The server rejected the key.' })
    render(<RemoteMcpToolsPanel projectId="p1" />)

    expect(await screen.findByText('The server rejected the key.')).toBeInTheDocument()
  })
})
