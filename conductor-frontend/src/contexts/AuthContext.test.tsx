import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, act, waitFor } from '@testing-library/react'
import { AuthProvider, useAuth } from './AuthContext'

const firebaseAuthState = vi.hoisted(() => ({ auth: { currentUser: null as unknown } }))

vi.mock('@/lib/firebase', () => ({
  getFirebaseAuth: () => firebaseAuthState.auth,
}))

vi.mock('firebase/auth', () => ({
  GoogleAuthProvider: class GoogleAuthProvider {
    setCustomParameters() {}
  },
  signInWithPopup: vi.fn(),
  signOut: vi.fn(),
  getIdToken: vi.fn(),
  signInWithEmailAndPassword: vi.fn(),
  createUserWithEmailAndPassword: vi.fn(),
  updateProfile: vi.fn(),
  sendEmailVerification: vi.fn(),
  sendPasswordResetEmail: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  apiPost: vi.fn(),
  setOnUnauthorized: vi.fn(),
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

async function renderAuth() {
  const ref: { current: ReturnType<typeof useAuth> | null } = { current: null }
  render(
    <AuthProvider>
      <TestConsumer onValues={(v) => { ref.current = v }} />
    </AuthProvider>
  )
  await waitFor(() => expect(ref.current?.loading).toBe(false))
  return ref
}

function apiError(status: number, code?: string) {
  return Object.assign(new Error(`Server error (${status})`), { status, code })
}

describe('AuthContext', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    document.cookie = 'access_token=; expires=Thu, 01 Jan 1970 00:00:00 GMT'
    vi.unstubAllEnvs()
    firebaseAuthState.auth.currentUser = null
  })

  it('starts with null user and null token when no stored token', async () => {
    const ref: { current: ReturnType<typeof useAuth> | null } = { current: null }

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { ref.current = v }} />
      </AuthProvider>
    )

    await waitFor(() => {
      expect(ref.current?.loading).toBe(false)
    })

    expect(ref.current?.user).toBeNull()
    expect(ref.current?.accessToken).toBeNull()
  })

  it('restores accessToken from localStorage on mount', async () => {
    localStorage.setItem('access_token', 'stored-token')
    const ref: { current: ReturnType<typeof useAuth> | null } = { current: null }

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { ref.current = v }} />
      </AuthProvider>
    )

    await waitFor(() => {
      expect(ref.current?.loading).toBe(false)
    })

    expect(ref.current?.accessToken).toBe('stored-token')
  })

  it('signInWithGoogle calls signInWithPopup and stores user + token', async () => {
    const mockFirebaseUser = { uid: 'firebase-uid', emailVerified: true }
    vi.mocked(firebaseAuth.signInWithPopup).mockResolvedValue({
      user: mockFirebaseUser,
    } as Awaited<ReturnType<typeof firebaseAuth.signInWithPopup>>)
    vi.mocked(firebaseAuth.getIdToken).mockResolvedValue('firebase-id-token')
    vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'backend-token', user: mockUser })

    const ref: { current: ReturnType<typeof useAuth> | null } = { current: null }

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { ref.current = v }} />
      </AuthProvider>
    )

    await waitFor(() => expect(ref.current?.loading).toBe(false))

    await act(async () => {
      await ref.current?.signInWithGoogle()
    })

    expect(firebaseAuth.signInWithPopup).toHaveBeenCalled()
    expect(firebaseAuth.getIdToken).toHaveBeenCalledWith(mockFirebaseUser, true)
    expect(api.apiPost).toHaveBeenCalledWith('/api/v1/auth/firebase', { idToken: 'firebase-id-token' })
    expect(ref.current?.user).toEqual(mockUser)
    expect(ref.current?.accessToken).toBe('backend-token')
    expect(localStorage.getItem('access_token')).toBe('backend-token')
  })

  it('signInWithGoogle sets signInError on failure', async () => {
    const firebaseError = Object.assign(new Error('popup-failed'), { code: 'auth/popup-closed-by-user' })
    vi.mocked(firebaseAuth.signInWithPopup).mockRejectedValue(firebaseError)

    const ref: { current: ReturnType<typeof useAuth> | null } = { current: null }

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { ref.current = v }} />
      </AuthProvider>
    )

    await waitFor(() => expect(ref.current?.loading).toBe(false))

    await act(async () => {
      try { await ref.current?.signInWithGoogle() } catch { /* expected */ }
    })

    expect(ref.current?.signInError).toBe('Sign in failed: auth/popup-closed-by-user')
    expect(ref.current?.user).toBeNull()
  })

  it('clears user and token after signOut', async () => {
    localStorage.setItem('access_token', 'backend-token')
    localStorage.setItem('user', JSON.stringify(mockUser))
    vi.mocked(firebaseAuth.signOut).mockResolvedValue()
    vi.mocked(api.apiPost).mockResolvedValue({})

    const ref: { current: ReturnType<typeof useAuth> | null } = { current: null }

    render(
      <AuthProvider>
        <TestConsumer onValues={(v) => { ref.current = v }} />
      </AuthProvider>
    )

    await waitFor(() => expect(ref.current?.user).toEqual(mockUser))

    await act(async () => { await ref.current?.signOut() })

    expect(ref.current?.user).toBeNull()
    expect(ref.current?.accessToken).toBeNull()
    expect(localStorage.getItem('access_token')).toBeNull()
  })

  describe('email + password (Firebase)', () => {
    it('signUpWithEmail creates the account, sets the name, sends verification and skips the backend', async () => {
      const fbUser = { uid: 'u', emailVerified: false }
      vi.mocked(firebaseAuth.createUserWithEmailAndPassword).mockResolvedValue({
        user: fbUser,
      } as Awaited<ReturnType<typeof firebaseAuth.createUserWithEmailAndPassword>>)
      const ref = await renderAuth()

      await act(async () => {
        await ref.current?.signUpWithEmail('Ada Lovelace', 'ada@example.com', 'correct-horse')
      })

      expect(firebaseAuth.createUserWithEmailAndPassword).toHaveBeenCalledWith(
        expect.anything(),
        'ada@example.com',
        'correct-horse',
      )
      expect(firebaseAuth.updateProfile).toHaveBeenCalledWith(fbUser, { displayName: 'Ada Lovelace' })
      expect(firebaseAuth.sendEmailVerification).toHaveBeenCalledWith(fbUser, {
        url: `${window.location.origin}/login`,
      })
      expect(api.apiPost).not.toHaveBeenCalled()
      expect(ref.current?.user).toBeNull()
    })

    it('signInWithEmail exchanges a verified user with a force-refreshed token', async () => {
      const fbUser = { uid: 'u', emailVerified: true, reload: vi.fn() }
      vi.mocked(firebaseAuth.signInWithEmailAndPassword).mockResolvedValue({
        user: fbUser,
      } as unknown as Awaited<ReturnType<typeof firebaseAuth.signInWithEmailAndPassword>>)
      vi.mocked(firebaseAuth.getIdToken).mockResolvedValue('fresh-token')
      vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'backend-token', user: mockUser })
      const ref = await renderAuth()

      await act(async () => {
        await ref.current?.signInWithEmail('ada@example.com', 'correct-horse')
      })

      expect(firebaseAuth.getIdToken).toHaveBeenCalledWith(fbUser, true)
      expect(api.apiPost).toHaveBeenCalledWith('/api/v1/auth/firebase', { idToken: 'fresh-token' })
      expect(ref.current?.user).toEqual(mockUser)
      expect(localStorage.getItem('access_token')).toBe('backend-token')
    })

    it('an unverified user never reaches the backend and throws the typed error', async () => {
      const reload = vi.fn().mockResolvedValue(undefined)
      const fbUser = { uid: 'u', emailVerified: false, reload }
      vi.mocked(firebaseAuth.signInWithEmailAndPassword).mockResolvedValue({
        user: fbUser,
      } as unknown as Awaited<ReturnType<typeof firebaseAuth.signInWithEmailAndPassword>>)
      const ref = await renderAuth()

      let caught: unknown
      await act(async () => {
        try { await ref.current?.signInWithEmail('ada@example.com', 'correct-horse') } catch (e) { caught = e }
      })

      expect(reload).toHaveBeenCalledTimes(1)
      expect(caught).toMatchObject({ code: 'auth/email-not-verified' })
      expect(api.apiPost).not.toHaveBeenCalled()
      expect(firebaseAuth.getIdToken).not.toHaveBeenCalled()
      expect(ref.current?.user).toBeNull()
    })

    it('proceeds when reload shows the email has just been verified', async () => {
      const fbUser = { uid: 'u', emailVerified: false, reload: vi.fn() }
      fbUser.reload.mockImplementation(async () => { fbUser.emailVerified = true })
      vi.mocked(firebaseAuth.signInWithEmailAndPassword).mockResolvedValue({
        user: fbUser,
      } as unknown as Awaited<ReturnType<typeof firebaseAuth.signInWithEmailAndPassword>>)
      vi.mocked(firebaseAuth.getIdToken).mockResolvedValue('fresh-token')
      vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'backend-token', user: mockUser })
      const ref = await renderAuth()

      await act(async () => {
        await ref.current?.signInWithEmail('ada@example.com', 'correct-horse')
      })

      expect(api.apiPost).toHaveBeenCalledWith('/api/v1/auth/firebase', { idToken: 'fresh-token' })
      expect(ref.current?.user).toEqual(mockUser)
    })

    it('maps a backend 403 EMAIL_NOT_VERIFIED to the typed error', async () => {
      const fbUser = { uid: 'u', emailVerified: true, reload: vi.fn() }
      vi.mocked(firebaseAuth.signInWithEmailAndPassword).mockResolvedValue({
        user: fbUser,
      } as unknown as Awaited<ReturnType<typeof firebaseAuth.signInWithEmailAndPassword>>)
      vi.mocked(firebaseAuth.getIdToken).mockResolvedValue('fresh-token')
      vi.mocked(api.apiPost).mockRejectedValue(apiError(403, 'EMAIL_NOT_VERIFIED'))
      const ref = await renderAuth()

      let caught: unknown
      await act(async () => {
        try { await ref.current?.signInWithEmail('ada@example.com', 'correct-horse') } catch (e) { caught = e }
      })

      expect(caught).toMatchObject({ code: 'auth/email-not-verified' })
      expect(ref.current?.user).toBeNull()
    })

    it('does not map other backend errors to the typed error', async () => {
      const fbUser = { uid: 'u', emailVerified: true, reload: vi.fn() }
      vi.mocked(firebaseAuth.signInWithEmailAndPassword).mockResolvedValue({
        user: fbUser,
      } as unknown as Awaited<ReturnType<typeof firebaseAuth.signInWithEmailAndPassword>>)
      vi.mocked(firebaseAuth.getIdToken).mockResolvedValue('fresh-token')
      const serverErr = apiError(500)
      vi.mocked(api.apiPost).mockRejectedValue(serverErr)
      const ref = await renderAuth()

      let caught: unknown
      await act(async () => {
        try { await ref.current?.signInWithEmail('ada@example.com', 'correct-horse') } catch (e) { caught = e }
      })

      expect(caught).toBe(serverErr)
    })

    it('resendVerificationEmail re-sends to the current Firebase user', async () => {
      const fbUser = { uid: 'u' }
      firebaseAuthState.auth.currentUser = fbUser
      const ref = await renderAuth()

      await act(async () => { await ref.current?.resendVerificationEmail() })

      expect(firebaseAuth.sendEmailVerification).toHaveBeenCalledWith(fbUser, {
        url: `${window.location.origin}/login`,
      })
    })

    it('sendPasswordReset calls sendPasswordResetEmail', async () => {
      const ref = await renderAuth()

      await act(async () => { await ref.current?.sendPasswordReset('ada@example.com') })

      expect(firebaseAuth.sendPasswordResetEmail).toHaveBeenCalledWith(expect.anything(), 'ada@example.com')
    })
  })

  describe('local auth mode', () => {
    beforeEach(() => {
      vi.stubEnv('NEXT_PUBLIC_AUTH_MODE', 'local')
    })

    it('signInWithEmail calls POST /api/v1/auth/local and stores accessToken', async () => {
      vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'local-token', user: mockUser })

      const ref: { current: ReturnType<typeof useAuth> | null } = { current: null }

      render(
        <AuthProvider>
          <TestConsumer onValues={(v) => { ref.current = v }} />
        </AuthProvider>
      )

      await waitFor(() => expect(ref.current?.loading).toBe(false))

      await act(async () => {
        await ref.current?.signInWithEmail('user@example.com', 'secret123')
      })

      expect(api.apiPost).toHaveBeenCalledWith('/api/v1/auth/local', {
        email: 'user@example.com',
        password: 'secret123',
      })
      expect(ref.current?.user).toEqual(mockUser)
      expect(ref.current?.accessToken).toBe('local-token')
      expect(localStorage.getItem('access_token')).toBe('local-token')
    })

    it('signInWithEmail does not call Firebase SDK', async () => {
      vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'local-token', user: mockUser })

      const ref: { current: ReturnType<typeof useAuth> | null } = { current: null }

      render(
        <AuthProvider>
          <TestConsumer onValues={(v) => { ref.current = v }} />
        </AuthProvider>
      )

      await waitFor(() => expect(ref.current?.loading).toBe(false))

      await act(async () => {
        await ref.current?.signInWithEmail('user@example.com', 'secret123')
      })

      expect(firebaseAuth.signInWithPopup).not.toHaveBeenCalled()
      expect(firebaseAuth.getIdToken).not.toHaveBeenCalled()
    })

    it('signOut clears token without calling Firebase signOut', async () => {
      vi.mocked(api.apiPost).mockResolvedValue({ accessToken: 'local-token', user: mockUser })

      const ref: { current: ReturnType<typeof useAuth> | null } = { current: null }

      render(
        <AuthProvider>
          <TestConsumer onValues={(v) => { ref.current = v }} />
        </AuthProvider>
      )

      await waitFor(() => expect(ref.current?.loading).toBe(false))

      await act(async () => {
        await ref.current?.signInWithEmail('user@example.com', 'secret123')
      })

      vi.mocked(api.apiPost).mockResolvedValue({})
      await act(async () => { await ref.current?.signOut() })

      expect(firebaseAuth.signOut).not.toHaveBeenCalled()
      expect(ref.current?.user).toBeNull()
      expect(ref.current?.accessToken).toBeNull()
      expect(localStorage.getItem('access_token')).toBeNull()
    })
  })
})
