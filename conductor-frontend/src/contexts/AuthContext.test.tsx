import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, act, waitFor } from '@testing-library/react'
import { AuthProvider, useAuth } from './AuthContext'

vi.mock('@/lib/firebase', () => ({
  getFirebaseAuth: () => ({}),
}))

vi.mock('firebase/auth', () => ({
  GoogleAuthProvider: class GoogleAuthProvider {},
  signInWithPopup: vi.fn(),
  signOut: vi.fn(),
  getIdToken: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  apiPost: vi.fn(),
}))

import * as firebaseAuth from 'firebase/auth'
import * as api from '@/lib/api'

const mockUser = {
  id: 'user-1',
  name: 'Test User',
  email: 'test@example.com',
  avatarUrl: null,
  displayName: null,
}

function TestConsumer({ onValues }: { onValues: (v: ReturnType<typeof useAuth>) => void }) {
  const values = useAuth()
  onValues(values)
  return null
}

describe('AuthContext', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    document.cookie = 'access_token=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
    vi.unstubAllEnvs()
  })

  it('starts with null user and null token when no stored token', async () => {
    let captured: ReturnType<typeof useAuth> | null = null

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </AuthProvider>
    )

    await waitFor(() => {
      expect(captured?.loading).toBe(false)
    })

    expect(captured?.user).toBeNull()
    expect(captured?.accessToken).toBeNull()
  })

  it('restores accessToken from localStorage on mount', async () => {
    localStorage.setItem('access_token', 'stored-token')
    let captured: ReturnType<typeof useAuth> | null = null

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </AuthProvider>
    )

    await waitFor(() => {
      expect(captured?.loading).toBe(false)
    })

    expect(captured?.accessToken).toBe('stored-token')
  })

  it('signIn calls signInWithPopup and stores user after Google auth', async () => {
    const mockFirebaseUser = { uid: 'firebase-uid' }
    vi.mocked(firebaseAuth.signInWithPopup).mockResolvedValue({
      user: mockFirebaseUser,
    } as Awaited<ReturnType<typeof firebaseAuth.signInWithPopup>>)
    vi.mocked(firebaseAuth.getIdToken).mockResolvedValue('firebase-id-token')
    vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'backend-token', user: mockUser })

    let captured: ReturnType<typeof useAuth> | null = null

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </AuthProvider>
    )

    await waitFor(() => expect(captured?.loading).toBe(false))

    await act(async () => {
      await captured?.signIn()
    })

    expect(firebaseAuth.signInWithPopup).toHaveBeenCalled()
    expect(api.apiPost).toHaveBeenCalledWith('/api/v1/auth/firebase', { idToken: 'firebase-id-token' })
    expect(captured?.user).toEqual(mockUser)
    expect(captured?.accessToken).toBe('backend-token')
    expect(localStorage.getItem('access_token')).toBe('backend-token')
  })

  it('clears user and token after signOut', async () => {
    localStorage.setItem('access_token', 'backend-token')
    localStorage.setItem('user', JSON.stringify(mockUser))
    vi.mocked(firebaseAuth.signOut).mockResolvedValue()
    vi.mocked(api.apiPost).mockResolvedValue({})

    let captured: ReturnType<typeof useAuth> | null = null

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </AuthProvider>
    )

    await waitFor(() => expect(captured?.user).toEqual(mockUser))

    await act(async () => { await captured?.signOut() })

    expect(captured?.user).toBeNull()
    expect(captured?.accessToken).toBeNull()
    expect(localStorage.getItem('access_token')).toBeNull()
  })

  describe('local auth mode', () => {
    beforeEach(() => {
      vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'local')
    })

    it('signIn with credentials calls POST /api/v1/auth/local and stores accessToken', async () => {
      vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'local-token', user: mockUser })

      let captured: ReturnType<typeof useAuth> | null = null

      render(
        <AuthProvider>
          <TestConsumer onValues={(v) => { captured = v }} />
        </AuthProvider>
      )

      await waitFor(() => expect(captured?.loading).toBe(false))

      await act(async () => {
        await captured?.signIn({ email: 'user@example.com', password: 'secret123' })
      })

      expect(api.apiPost).toHaveBeenCalledWith('/api/v1/auth/local', {
        email: 'user@example.com',
        password: 'secret123',
      })
      expect(captured?.user).toEqual(mockUser)
      expect(captured?.accessToken).toBe('local-token')
      expect(localStorage.getItem('access_token')).toBe('local-token')
    })

    it('signIn with credentials does not call Firebase SDK', async () => {
      vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'local-token', user: mockUser })

      let captured: ReturnType<typeof useAuth> | null = null

      render(
        <AuthProvider>
          <TestConsumer onValues={(v) => { captured = v }} />
        </AuthProvider>
      )

      await waitFor(() => expect(captured?.loading).toBe(false))

      await act(async () => {
        await captured?.signIn({ email: 'user@example.com', password: 'secret123' })
      })

      expect(firebaseAuth.signInWithPopup).not.toHaveBeenCalled()
      expect(firebaseAuth.getIdToken).not.toHaveBeenCalled()
    })

    it('signOut clears token without calling Firebase signOut', async () => {
      vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'local-token', user: mockUser })

      let captured: ReturnType<typeof useAuth> | null = null

      render(
        <AuthProvider>
          <TestConsumer onValues={(v) => { captured = v }} />
        </AuthProvider>
      )

      await waitFor(() => expect(captured?.loading).toBe(false))

      await act(async () => {
        await captured?.signIn({ email: 'user@example.com', password: 'secret123' })
      })

      vi.mocked(api.apiPost).mockResolvedValue({})
      await act(async () => { await captured?.signOut() })

      expect(firebaseAuth.signOut).not.toHaveBeenCalled()
      expect(captured?.user).toBeNull()
      expect(captured?.accessToken).toBeNull()
      expect(localStorage.getItem('access_token')).toBeNull()
    })
  })
})
