'use client'

import { createContext, useContext, useEffect, useState, ReactNode } from 'react'
import type { User, AuthResponse } from '@/types'
import { apiPost } from '@/lib/api'

interface AuthContextValue {
  user: User | null
  accessToken: string | null
  loading: boolean
  signIn: (credentials?: { email: string; password: string }) => Promise<void>
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
    document.cookie = `access_token=${token}; path=/; SameSite=Lax`
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

  useEffect(() => {
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

    const isLocalMode = process.env.NEXT_PUBLIC_AUTH_MODE === 'local'
    if (!isLocalMode) {
      import('@/lib/firebase').then(({ getFirebaseAuth }) =>
        import('firebase/auth').then(({ getRedirectResult, getIdToken }) =>
          getRedirectResult(getFirebaseAuth()).then(async (credential) => {
            if (credential) {
              const idToken = await getIdToken(credential.user)
              const response = await apiPost<AuthResponse>('/api/v1/auth/firebase', { idToken })
              setUser(response.user)
              setAccessToken(response.accessToken)
              localStorage.setItem('access_token', response.accessToken)
              localStorage.setItem('user', JSON.stringify(response.user))
              setAccessTokenCookie(response.accessToken)
              window.location.href = new URLSearchParams(window.location.search).get('next') ?? '/app/projects'
            }
          }).catch(() => {}).finally(() => setLoading(false))
        )
      )
    } else {
      setLoading(false)
    }
  }, [])

  async function signIn(credentials?: { email: string; password: string }): Promise<void> {
    const isLocalMode = process.env.NEXT_PUBLIC_AUTH_MODE === 'local'

    if (isLocalMode && credentials) {
      const response = await apiPost<AuthResponse>('/api/v1/auth/local', credentials)
      setUser(response.user)
      setAccessToken(response.accessToken)
      localStorage.setItem('access_token', response.accessToken)
      localStorage.setItem('user', JSON.stringify(response.user))
      setAccessTokenCookie(response.accessToken)
      return
    }

    const { getFirebaseAuth } = await import('@/lib/firebase')
    const { GoogleAuthProvider, signInWithRedirect } = await import('firebase/auth')

    const auth = getFirebaseAuth()
    const provider = new GoogleAuthProvider()
    await signInWithRedirect(auth, provider)
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
      const { getFirebaseAuth } = await import('@/lib/firebase')
      const { signOut: firebaseSignOut } = await import('firebase/auth')
      await firebaseSignOut(getFirebaseAuth())
    }

    setUser(null)
    setAccessToken(null)
    localStorage.removeItem('access_token')
    localStorage.removeItem('user')
    clearAccessTokenCookie()
  }

  return (
    <AuthContext.Provider value={{ user, accessToken, loading, signIn, signOut }}>
      {children}
    </AuthContext.Provider>
  )
}
