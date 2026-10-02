'use client'

import { createContext, useContext, useEffect, useState, ReactNode } from 'react'
import type { User, AuthResponse } from '@/types'
import { apiPost, setOnUnauthorized } from '@/lib/api'
import { getFirebaseAuth } from '@/lib/firebase'
import {
  createEmailAccount,
  exchangeFirebaseUser,
  resendVerificationEmail as resendFirebaseVerificationEmail,
  sendPasswordReset as sendFirebasePasswordReset,
  signInWithEmailExchange,
} from '@/lib/firebase-auth-flow'
import { authErrorMessage, isEmailNotVerifiedError } from '@/lib/auth-errors'
import { GoogleAuthProvider, signInWithPopup, signOut as firebaseSignOut } from 'firebase/auth'

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
      window.location.href = '/login'
    })

    const storedToken = localStorage.getItem('access_token')
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
      const provider = new GoogleAuthProvider()
      provider.setCustomParameters({ prompt: 'select_account' })
      const result = await signInWithPopup(getFirebaseAuth(), provider)
      storeSession(await exchangeFirebaseUser(result.user))
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
    storeSession(await signInWithEmailExchange(email, password))
  }

  async function signUpWithEmail(name: string, email: string, password: string): Promise<void> {
    await createEmailAccount(name, email, password)
  }

  async function resendVerificationEmail(): Promise<void> {
    await resendFirebaseVerificationEmail()
  }

  async function sendPasswordReset(email: string): Promise<void> {
    await sendFirebasePasswordReset(email)
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
      await firebaseSignOut(getFirebaseAuth())
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
