'use client'

import { loginUrlFor } from '@/lib/safe-next'
import { createContext, useContext, useEffect, useState, ReactNode } from 'react'
import type { User, AuthResponse } from '@/types'
import { apiPost, setOnUnauthorized } from '@/lib/api'
import { authErrorMessage, isEmailNotVerifiedError } from '@/lib/auth-errors'

// The Firebase SDK is large and only the sign-in, sign-up and sign-out paths touch it, so it is loaded
// on demand instead of riding in the bundle every signed-in page ships. One shared promise: the first
// caller starts the download, the rest reuse it. It is also started early for a signed-out visitor (see
// the mount effect), so by the time they press "Sign in with Google" the SDK is already here and the
// popup still opens inside the click's user activation.
let firebaseModules: Promise<{
  auth: typeof import('firebase/auth')
  app: typeof import('@/lib/firebase')
  flow: typeof import('@/lib/firebase-auth-flow')
}> | null = null

function loadFirebase() {
  if (!firebaseModules) {
    firebaseModules = Promise.all([
      import('firebase/auth'),
      import('@/lib/firebase'),
      import('@/lib/firebase-auth-flow'),
    ])
      .then(([auth, app, flow]) => ({ auth, app, flow }))
      .catch((err) => {
        firebaseModules = null // let the next attempt retry the download
        throw err
      })
  }
  return firebaseModules
}

interface AuthContextValue {
  user: User | null
  accessToken: string | null
  loading: boolean
  signInError: string | null
  signInWithGoogle: () => Promise<void>
  /** Local dev mode posts to /api/v1/auth/local; otherwise Firebase email/password. Throws on failure. */
  signInWithEmail: (email: string, password: string) => Promise<void>
  /** Creates the Firebase account and sends the verification email. No session until verified. */
  signUpWithEmail: (name: string, email: string, password: string) => Promise<void>
  resendVerificationEmail: () => Promise<void>
  sendPasswordReset: (email: string) => Promise<void>
  signOut: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}

function setAccessTokenCookie(token: string) {
  if (typeof document !== 'undefined') {
    const maxAge = 24 * 60 * 60 // 24h, matches JWT expiry
    document.cookie = `access_token=${token}; path=/; SameSite=Lax; Max-Age=${maxAge}`
  }
}

function clearAccessTokenCookie() {
  if (typeof document !== 'undefined') {
    document.cookie = 'access_token=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT'
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [accessToken, setAccessToken] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [signInError, setSignInError] = useState<string | null>(null)

  function clearLocalAuth() {
    setUser(null)
    setAccessToken(null)
    localStorage.removeItem('access_token')
    localStorage.removeItem('user')
    clearAccessTokenCookie()
  }

  useEffect(() => {
    setOnUnauthorized(() => {
      clearLocalAuth()
      // Come back to the same page (query string included) after signing in again.
      const here = window.location.pathname + window.location.search
      window.location.href = here.startsWith('/app') ? loginUrlFor(here) : '/login'
    })

    const storedToken = localStorage.getItem('access_token')
    if (!storedToken && process.env.NEXT_PUBLIC_AUTH_MODE !== 'local') {
      loadFirebase().catch(() => { /* surfaced when a sign-in is actually attempted */ })
    }
    const storedUser = localStorage.getItem('user')
    if (storedToken) {
      setAccessToken(storedToken)
      setAccessTokenCookie(storedToken)
      if (storedUser) {
        try {
          setUser(JSON.parse(storedUser))
        } catch {
          // Ignore malformed stored user
        }
      }
    }

    setLoading(false)
  }, [])

  function storeSession(response: AuthResponse) {
    setUser(response.user)
    setAccessToken(response.accessToken)
    localStorage.setItem('access_token', response.accessToken)
    localStorage.setItem('user', JSON.stringify(response.user))
    setAccessTokenCookie(response.accessToken)
  }

  async function signInWithGoogle(): Promise<void> {
    setSignInError(null)
    try {
      const { auth, app, flow } = await loadFirebase()
      const provider = new auth.GoogleAuthProvider()
      provider.setCustomParameters({ prompt: 'select_account' })
      const result = await auth.signInWithPopup(app.getFirebaseAuth(), provider)
      storeSession(await flow.exchangeFirebaseUser(result.user))
    } catch (err) {
      const code = (err as { code?: string })?.code
      if (isEmailNotVerifiedError(err)) {
        setSignInError(authErrorMessage(err))
      } else {
        setSignInError(code ? `Sign in failed: ${code}` : 'Sign in failed. Please try again.')
      }
      throw err
    }
  }

  async function signInWithEmail(email: string, password: string): Promise<void> {
    if (process.env.NEXT_PUBLIC_AUTH_MODE === 'local') {
      const response = await apiPost<AuthResponse>('/api/v1/auth/local', { email, password })
      storeSession(response)
      return
    }
    const { flow } = await loadFirebase()
    storeSession(await flow.signInWithEmailExchange(email, password))
  }

  async function signUpWithEmail(name: string, email: string, password: string): Promise<void> {
    const { flow } = await loadFirebase()
    await flow.createEmailAccount(name, email, password)
  }

  async function resendVerificationEmail(): Promise<void> {
    const { flow } = await loadFirebase()
    await flow.resendVerificationEmail()
  }

  async function sendPasswordReset(email: string): Promise<void> {
    const { flow } = await loadFirebase()
    await flow.sendPasswordReset(email)
  }

  async function signOut(): Promise<void> {
    const token = accessToken
    if (token) {
      try {
        await apiPost('/api/v1/auth/logout', {}, token)
      } catch {
        // Continue sign out even if backend call fails
      }
    }

    const isLocalMode = process.env.NEXT_PUBLIC_AUTH_MODE === 'local'
    if (!isLocalMode) {
      const { auth, app } = await loadFirebase()
      await auth.signOut(app.getFirebaseAuth())
    }

    setUser(null)
    setAccessToken(null)
    localStorage.removeItem('access_token')
    localStorage.removeItem('user')
    clearAccessTokenCookie()
  }

  return (
    <AuthContext.Provider
      value={{
        user,
        accessToken,
        loading,
        signInError,
        signInWithGoogle,
        signInWithEmail,
        signUpWithEmail,
        resendVerificationEmail,
        sendPasswordReset,
        signOut,
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}
