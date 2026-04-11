import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, act, waitFor } from '@testing-library/react'
import { AuthProvider, useAuth } from './AuthContext'

vi.mock('@/lib/firebase', () => ({
  getFirebaseAuth: () => ({}),
}))

vi.mock('firebase/auth', () => ({
  GoogleAuthProvider: class GoogleAuthProvider {},
  signInWithRedirect: vi.fn(),
  getRedirectResult: vi.fn().mockResolvedValue(null),
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
    vi.mocked(firebaseAuth.getRedirectResult).mockResolvedValue(null)
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

  it('stores user in AuthContext after Google redirect result on mount', async () => {
    const mockFirebaseUser = { uid: 'firebase-uid' }
    vi.mocked(firebaseAuth.getRedirectResult).mockResolvedValue({
      user: mockFirebaseUser,
    } as Awaited<ReturnType<typeof firebaseAuth.getRedirectResult>>)
    vi.mocked(firebaseAuth.getIdToken).mockResolvedValue('firebase-id-token')
    vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'backend-token', user: mockUser })

    // jsdom doesn't support navigation — stub it
    const locationSpy = vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      search: '',
      href: '',
    } as Location)

    let captured: ReturnType<typeof useAuth> | null = null

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </AuthProvider>
    )

    await waitFor(() => {
      expect(captured?.user).toEqual(mockUser)
    })

    expect(captured?.accessToken).toBe('backend-token')
    expect(localStorage.getItem('access_token')).toBe('backend-token')

    locationSpy.mockRestore()
  })

  it('calls POST /api/v1/auth/firebase with Firebase ID token on redirect result', async () => {
    const mockFirebaseUser = { uid: 'firebase-uid' }
    vi.mocked(firebaseAuth.getRedirectResult).mockResolvedValue({
      user: mockFirebaseUser,
    } as Awaited<ReturnType<typeof firebaseAuth.getRedirectResult>>)
    vi.mocked(firebaseAuth.getIdToken).mockResolvedValue('firebase-id-token')
    vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'backend-token', user: mockUser })

    const locationSpy = vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      search: '',
      href: '',
    } as Location)

    let captured: ReturnType<typeof useAuth> | null = null

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </AuthProvider>
    )

    await waitFor(() => expect(captured?.user).toEqual(mockUser))

    expect(api.apiPost).toHaveBeenCalledWith('/api/v1/auth/firebase', { idToken: 'firebase-id-token' })

    locationSpy.mockRestore()
  })

  it('signIn calls signInWithRedirect', async () => {
    vi.mocked(firebaseAuth.signInWithRedirect).mockResolvedValue(undefined)

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

    expect(firebaseAuth.signInWithRedirect).toHaveBeenCalled()
  })

  it('clears user and token after signOut', async () => {
    const mockFirebaseUser = { uid: 'firebase-uid' }
    vi.mocked(firebaseAuth.getRedirectResult).mockResolvedValue({
      user: mockFirebaseUser,
    } as Awaited<ReturnType<typeof firebaseAuth.getRedirectResult>>)
    vi.mocked(firebaseAuth.getIdToken).mockResolvedValue('firebase-id-token')
    vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'backend-token', user: mockUser })
    vi.mocked(firebaseAuth.signOut).mockResolvedValue()

    const locationSpy = vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      search: '',
      href: '',
    } as Location)

    let captured: ReturnType<typeof useAuth> | null = null

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </AuthProvider>
    )

    await waitFor(() => expect(captured?.user).toEqual(mockUser))

    vi.mocked(api.apiPost).mockResolvedValue({})
    await act(async () => { await captured?.signOut() })

    expect(captured?.user).toBeNull()
    expect(captured?.accessToken).toBeNull()
    expect(localStorage.getItem('access_token')).toBeNull()

    locationSpy.mockRestore()
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

      expect(firebaseAuth.signInWithRedirect).not.toHaveBeenCalled()
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
