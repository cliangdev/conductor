import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { OrgProvider, useOrg, OrgContextValue } from './OrgContext'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

import * as api from '@/lib/api'

const mockOrgs = [
  { id: 'org-1', name: 'Acme Corp', slug: 'acme-corp', createdAt: '2024-01-01' },
  { id: 'org-2', name: 'Beta Inc', slug: 'beta-inc', createdAt: '2024-01-02' },
]

function TestConsumer({ onValues }: { onValues: (v: OrgContextValue) => void }) {
  const values = useOrg()
  onValues(values)
  return null
}

describe('OrgContext', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('fetches orgs on mount and sets activeOrg to first', async () => {
    vi.mocked(api.apiGet).mockResolvedValue(mockOrgs)
    const captured: { current: OrgContextValue | null } = { current: null }

    render(
      <OrgProvider>
        <TestConsumer onValues={(v) => { captured.current = v }} />
      </OrgProvider>
    )

    await waitFor(() => expect(captured.current?.loading).toBe(false))

    expect(captured.current?.orgs).toEqual(mockOrgs)
    expect(captured.current?.activeOrg?.id).toBe('org-1')
    expect(captured.current?.needsOnboarding).toBe(false)
    expect(api.apiGet).toHaveBeenCalledWith('/api/v1/users/me/orgs', 'test-token')
  })

  it('sets needsOnboarding true when user has no orgs', async () => {
    vi.mocked(api.apiGet).mockResolvedValue([])
    const captured: { current: OrgContextValue | null } = { current: null }

    render(
      <OrgProvider>
        <TestConsumer onValues={(v) => { captured.current = v }} />
      </OrgProvider>
    )

    await waitFor(() => expect(captured.current?.loading).toBe(false))

    expect(captured.current?.orgs).toEqual([])
    expect(captured.current?.activeOrg).toBeNull()
    expect(captured.current?.needsOnboarding).toBe(true)
  })

  it('exposes refetch that re-fetches orgs', async () => {
    vi.mocked(api.apiGet).mockResolvedValueOnce([]).mockResolvedValueOnce(mockOrgs)
    const captured: { current: OrgContextValue | null } = { current: null }

    render(
      <OrgProvider>
        <TestConsumer onValues={(v) => { captured.current = v }} />
      </OrgProvider>
    )

    await waitFor(() => expect(captured.current?.loading).toBe(false))
    expect(captured.current?.needsOnboarding).toBe(true)

    await captured.current!.refetch()

    await waitFor(() => expect(captured.current?.orgs).toEqual(mockOrgs))
    expect(captured.current?.needsOnboarding).toBe(false)
    expect(captured.current?.activeOrg?.id).toBe('org-1')
  })
})
