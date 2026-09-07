import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token', user: { id: 'u-1', email: 'me@example.com' } }),
}))

import { PermissionsProvider, useCan, usePermissions } from './PermissionsContext'
import { invalidateMembersCache } from '@/lib/workflows'

const PROJECT = 'p-1'

function Probe() {
  const { role, loading } = usePermissions()
  const canManage = useCan('integration.appCredential.manage')
  return (
    <div>
      <span data-testid="role">{role ?? 'none'}</span>
      <span data-testid="loading">{String(loading)}</span>
      <span data-testid="can">{String(canManage)}</span>
    </div>
  )
}

const fetchMock = vi.fn()

beforeEach(() => {
  invalidateMembersCache(PROJECT)
  localStorage.clear()
  fetchMock.mockReset()
  vi.stubEnv('NEXT_PUBLIC_API_URL', 'https://api.test')
  vi.stubGlobal('fetch', fetchMock)
})

function serverSays(role: string) {
  fetchMock.mockResolvedValue({
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    json: async () => [{ userId: 'u-1', name: 'Me', email: 'me@example.com', avatarUrl: null, role, joinedAt: 'x' }],
  })
}

describe('PermissionsProvider', () => {
  it('seeds the role from the stored member list, then replaces it with what the server says now', async () => {
    // The browser stored this list while the viewer was still a CREATOR; since then another admin
    // promoted them. The stored list must not be the last word.
    localStorage.setItem(
      `members_${PROJECT}`,
      JSON.stringify([{ userId: 'u-1', name: 'Me', email: 'me@example.com', avatarUrl: null, role: 'CREATOR', joinedAt: 'x' }])
    )
    serverSays('ADMIN')

    render(
      <PermissionsProvider projectId={PROJECT}>
        <Probe />
      </PermissionsProvider>
    )

    // Seeded synchronously so nothing flashes...
    expect(screen.getByTestId('role').textContent).toBe('CREATOR')
    // ...and corrected as soon as the server answers.
    await waitFor(() => expect(screen.getByTestId('role').textContent).toBe('ADMIN'))
    expect(screen.getByTestId('can').textContent).toBe('true')
    expect(screen.getByTestId('loading').textContent).toBe('false')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0][0])).toContain(`/api/v1/projects/${PROJECT}/members`)
  })

  it('resolves the role from the server when nothing is cached', async () => {
    serverSays('ADMIN')
    render(
      <PermissionsProvider projectId={PROJECT}>
        <Probe />
      </PermissionsProvider>
    )
    expect(screen.getByTestId('role').textContent).toBe('none')
    await waitFor(() => expect(screen.getByTestId('can').textContent).toBe('true'))
  })

  it('treats a failed member fetch as no role, without throwing', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 403, headers: { get: () => 'application/json' }, json: async () => ({ detail: 'no' }) })
    render(
      <PermissionsProvider projectId={PROJECT}>
        <Probe />
      </PermissionsProvider>
    )
    await waitFor(() => expect(screen.getByTestId('loading').textContent).toBe('false'))
    expect(screen.getByTestId('can').textContent).toBe('false')
  })
})
