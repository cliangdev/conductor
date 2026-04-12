'use client'

export const dynamic = 'force-dynamic'

import { useSearchParams } from 'next/navigation'
import { useState, Suspense } from 'react'
import { apiGet, apiPost } from '@/lib/api'

interface UserApiKey {
  id: string
  maskedKey: string
  label: string
  createdAt: string
}

interface CreateApiKeyResponse {
  id: string
  key: string
  maskedKey: string
  label: string
  createdAt: string
}

function CliLoginContent() {
  const searchParams = useSearchParams()
  const port = searchParams.get('port')
  const [status, setStatus] = useState<'idle' | 'loading' | 'has-keys' | 'success' | 'error'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [pastedKey, setPastedKey] = useState('')
  const [pasteError, setPasteError] = useState<string | null>(null)
  const [pasteLoading, setPasteLoading] = useState(false)

  // Shared: redirect to CLI callback with the resolved key
  async function finishWithKey(apiKey: string, accessToken: string) {
    const projects = await apiGet<Array<{ id: string; name: string }>>('/api/v1/projects', accessToken)
    if (projects.length === 0) {
      throw new Error('No projects found — create a project in Conductor first.')
    }
    const project = projects[0]!
    const profile = await apiGet<{ email: string }>('/api/v1/auth/me', accessToken)

    const callbackUrl = new URL(`http://localhost:${port}/oauth/callback`)
    callbackUrl.searchParams.set('apiKey', apiKey)
    callbackUrl.searchParams.set('projectId', project.id)
    callbackUrl.searchParams.set('projectName', project.name)
    callbackUrl.searchParams.set('email', profile.email)
    window.location.href = callbackUrl.toString()
    setStatus('success')
  }

  async function handleSignIn() {
    if (!port) {
      setError('Missing port parameter')
      return
    }
    setStatus('loading')
    setError(null)
    try {
      const { getFirebaseAuth } = await import('@/lib/firebase')
      const { GoogleAuthProvider, signInWithPopup, getIdToken } = await import('firebase/auth')
      const auth = getFirebaseAuth()
      const provider = new GoogleAuthProvider()
      const credential = await signInWithPopup(auth, provider)
      const idToken = await getIdToken(credential.user)

      const { accessToken } = await apiPost<{ accessToken: string }>('/api/v1/auth/firebase', { idToken })

      // Check if user already has active API keys
      const existingKeys = await apiGet<UserApiKey[]>('/api/v1/api-keys', accessToken)

      if (existingKeys.length === 0) {
        // First-time user: auto-create a key
        const created = await apiPost<CreateApiKeyResponse>('/api/v1/api-keys', { label: 'CLI key' }, accessToken)
        await finishWithKey(created.key, accessToken)
      } else {
        // User already has keys — show paste UI
        // Store accessToken in state for use when the user submits
        ;(window as unknown as Record<string, unknown>)['_cliAccessToken'] = accessToken
        setStatus('has-keys')
      }
    } catch (err) {
      setError((err as Error).message ?? 'Authentication failed')
      setStatus('error')
    }
  }

  async function handleUseExistingKey() {
    const key = pastedKey.trim()
    if (!key) {
      setPasteError('Please paste your API key.')
      return
    }
    setPasteError(null)
    setPasteLoading(true)
    try {
      const accessToken = (window as unknown as Record<string, unknown>)['_cliAccessToken'] as string
      // Validate the pasted key by fetching projects with it
      const projects = await apiGet<Array<{ id: string; name: string }>>('/api/v1/projects', key)
      if (projects.length === 0) {
        throw new Error('No projects found for this key.')
      }
      await finishWithKey(key, accessToken)
    } catch {
      setPasteError('Invalid or expired API key. Check the key and try again.')
    } finally {
      setPasteLoading(false)
    }
  }

  async function handleCreateNewKey() {
    setPasteLoading(true)
    setPasteError(null)
    try {
      const accessToken = (window as unknown as Record<string, unknown>)['_cliAccessToken'] as string
      const created = await apiPost<CreateApiKeyResponse>('/api/v1/api-keys', { label: 'CLI key' }, accessToken)
      await finishWithKey(created.key, accessToken)
    } catch (err) {
      setPasteError((err as Error).message ?? 'Failed to create key.')
    } finally {
      setPasteLoading(false)
    }
  }

  if (status === 'success') {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background px-4">
        <div className="w-full max-w-sm rounded-xl border border-border bg-card p-8 shadow-sm text-center">
          <h1 className="mb-2 text-2xl font-bold text-foreground">Authentication successful!</h1>
          <p className="text-sm text-muted-foreground">You can close this tab and return to the terminal.</p>
        </div>
      </div>
    )
  }

  if (status === 'has-keys') {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background px-4">
        <div className="w-full max-w-sm rounded-xl border border-border bg-card p-8 shadow-sm">
          <h1 className="mb-2 text-2xl font-bold text-foreground text-center">Use existing API key</h1>
          <p className="mb-6 text-sm text-muted-foreground text-center">
            You already have an API key. Paste it below to use it on this machine, or create a new one.
          </p>
          <div className="space-y-3">
            <input
              type="text"
              value={pastedKey}
              onChange={(e) => setPastedKey(e.target.value)}
              placeholder="ck_..."
              className="w-full rounded-md border border-border bg-background px-3 py-2 text-sm font-mono placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
            />
            {pasteError && <p className="text-sm text-destructive">{pasteError}</p>}
            <button
              onClick={handleUseExistingKey}
              disabled={pasteLoading}
              className="w-full rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              {pasteLoading ? 'Validating...' : 'Use this key'}
            </button>
            <div className="relative flex items-center py-1">
              <div className="flex-grow border-t border-border" />
              <span className="mx-3 text-xs text-muted-foreground">or</span>
              <div className="flex-grow border-t border-border" />
            </div>
            <button
              onClick={handleCreateNewKey}
              disabled={pasteLoading}
              className="w-full rounded-md border border-border bg-background px-4 py-2 text-sm font-medium text-foreground hover:bg-muted disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Create a new key
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-background px-4">
      <div className="w-full max-w-sm rounded-xl border border-border bg-card p-8 shadow-sm">
        <h1 className="mb-2 text-2xl font-bold text-foreground text-center">Conductor CLI Login</h1>
        <p className="mb-8 text-sm text-muted-foreground text-center">
          Sign in to authenticate your terminal session.
        </p>
        <button
          onClick={handleSignIn}
          disabled={status === 'loading'}
          className="w-full flex items-center justify-center gap-3 rounded-md border border-border bg-white px-4 py-2.5 text-sm font-medium text-gray-700 shadow-sm transition-colors hover:bg-gray-50 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48" className="h-5 w-5 shrink-0">
            <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/>
            <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/>
            <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/>
            <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.18 1.48-4.97 2.31-8.16 2.31-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/>
            <path fill="none" d="M0 0h48v48H0z"/>
          </svg>
          {status === 'loading' ? 'Signing in...' : 'Sign in with Google'}
        </button>
        {(status === 'error') && error && <p className="mt-3 text-sm text-destructive text-center">{error}</p>}
      </div>
    </div>
  )
}

export default function CliLoginPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center bg-background text-muted-foreground">Loading...</div>}>
      <CliLoginContent />
    </Suspense>
  )
}
