'use client'

export const dynamic = 'force-dynamic'

import { useSearchParams } from 'next/navigation'
import { useState, Suspense } from 'react'
import { Button } from '@/components/ui/button'
import { AuthCard, AuthDivider, GoogleSignInButton } from '@/components/auth/AuthCard'
import { EmailPasswordForm } from '@/components/auth/EmailPasswordForm'
import { apiGet, apiPost } from '@/lib/api'
import { authErrorMessage, isEmailNotVerifiedError } from '@/lib/auth-errors'
import {
  createEmailAccount,
  exchangeFirebaseUser,
  resendVerificationEmail,
  sendPasswordReset,
  signInWithEmailExchange,
} from '@/lib/firebase-auth-flow'

const CLI_KEY_LABEL = 'CLI key'

interface UserApiKey {
  id: string
  key: string | null
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
  const [status, setStatus] = useState<'idle' | 'loading' | 'pick' | 'pick-project' | 'success' | 'error'>('idle')
  const [isCreating, setIsCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [existingKeys, setExistingKeys] = useState<UserApiKey[]>([])
  const [accessTokenStore, setAccessTokenStore] = useState<string>('')
  const [projectChoices, setProjectChoices] = useState<Array<{ id: string; name: string }>>([])
  const [pendingApiKey, setPendingApiKey] = useState<string>('')
  const [profileEmail, setProfileEmail] = useState<string>('')

  function redirectToCli(apiKey: string, project: { id: string; name: string }, email: string) {
    const callbackUrl = new URL(`http://localhost:${port}/oauth/callback`)
    callbackUrl.searchParams.set('apiKey', apiKey)
    callbackUrl.searchParams.set('projectId', project.id)
    callbackUrl.searchParams.set('projectName', project.name)
    callbackUrl.searchParams.set('email', email)
    window.location.href = callbackUrl.toString()
    setStatus('success')
  }

  async function finishWithKey(apiKey: string, accessToken: string) {
    const projects = await apiGet<Array<{ id: string; name: string }>>('/api/v1/projects', accessToken)
    if (projects.length === 0) {
      throw new Error('No projects found — create a project in Conductor first.')
    }
    const profile = await apiGet<{ email: string }>('/api/v1/auth/me', accessToken)

    if (projects.length === 1) {
      redirectToCli(apiKey, projects[0]!, profile.email)
      return
    }
    // Multiple projects — let the user choose which one the CLI starts on rather
    // than silently binding to the first (which mis-aimed the daemon's syncs).
    setPendingApiKey(apiKey)
    setProfileEmail(profile.email)
    setProjectChoices(projects)
    setStatus('pick-project')
  }

  function handlePickProject(project: { id: string; name: string }) {
    redirectToCli(pendingApiKey, project, profileEmail)
  }

  /** Shared continuation once we hold an app accessToken: find or mint the CLI key, then finish. */
  async function continueWithAccessToken(accessToken: string) {
    const allKeys = await apiGet<UserApiKey[]>('/api/v1/api-keys', accessToken)
    const cliKeys = allKeys.filter(k => k.label === CLI_KEY_LABEL)

    if (cliKeys.length === 0) {
      const created = await apiPost<CreateApiKeyResponse>('/api/v1/api-keys', { label: CLI_KEY_LABEL }, accessToken)
      await finishWithKey(created.key, accessToken)
    } else {
      setExistingKeys(cliKeys)
      setAccessTokenStore(accessToken)
      setStatus('pick')
    }
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
      const { GoogleAuthProvider, signInWithPopup } = await import('firebase/auth')
      const auth = getFirebaseAuth()
      const provider = new GoogleAuthProvider()
      const credential = await signInWithPopup(auth, provider)
      const { accessToken } = await exchangeFirebaseUser(credential.user)
      await continueWithAccessToken(accessToken)
    } catch (err) {
      setError(
        isEmailNotVerifiedError(err) ? authErrorMessage(err) : ((err as Error).message ?? 'Authentication failed'),
      )
      setStatus('error')
    }
  }

  /** Email/password path. Auth failures throw back into the form (which owns their messaging);
   * everything after we hold an accessToken reports on the page, like the Google path. */
  async function handleEmailSignIn(email: string, password: string) {
    if (!port) {
      setError('Missing port parameter')
      setStatus('error')
      return
    }
    setError(null)
    const { accessToken } = await signInWithEmailExchange(email, password)
    setStatus('loading')
    try {
      await continueWithAccessToken(accessToken)
    } catch (err) {
      setError((err as Error).message ?? 'Authentication failed')
      setStatus('error')
    }
  }

  async function handleUseKey(key: string) {
    try {
      await finishWithKey(key, accessTokenStore)
    } catch (err) {
      setError((err as Error).message ?? 'Failed to complete login')
      setStatus('error')
    }
  }

  async function handleCreateNew() {
    setIsCreating(true)
    try {
      const created = await apiPost<CreateApiKeyResponse>('/api/v1/api-keys', { label: CLI_KEY_LABEL }, accessTokenStore)
      await finishWithKey(created.key, accessTokenStore)
    } catch (err) {
      setError((err as Error).message ?? 'Failed to create key')
      setStatus('error')
    } finally {
      setIsCreating(false)
    }
  }

  if (status === 'success') {
    return (
      <AuthCard className="text-center">
        <h1 className="mb-2 text-2xl font-bold text-foreground">Authentication successful!</h1>
        <p className="text-sm text-muted-foreground">You can close this tab and return to the terminal.</p>
      </AuthCard>
    )
  }

  if (status === 'pick') {
    return (
      <AuthCard>
        <h1 className="mb-2 text-2xl font-bold text-foreground text-center">Select an API key</h1>
        <p className="mb-5 text-sm text-muted-foreground text-center">
          Choose an existing key to use on this machine, or create a new one.
        </p>
        <div className="space-y-2 mb-5">
          {existingKeys.map((k) => (
            <div key={k.id} className="flex items-center justify-between rounded-md border border-border bg-background px-3 py-2.5">
              <div className="min-w-0">
                <p className="text-sm font-mono text-foreground truncate">{k.maskedKey}</p>
                <p className="text-xs text-muted-foreground">
                  Created {new Date(k.createdAt).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })}
                </p>
              </div>
              {k.key ? (
                <Button size="sm" className="ml-3 shrink-0" onClick={() => handleUseKey(k.key!)}>
                  Use
                </Button>
              ) : (
                <span className="ml-3 shrink-0 text-xs text-muted-foreground italic">unavailable</span>
              )}
            </div>
          ))}
        </div>
        <AuthDivider className="mb-4" />
        <Button
          variant="outline"
          className="w-full"
          onClick={handleCreateNew}
          disabled={isCreating}
        >
          Create a new key
        </Button>
        {error && <p className="mt-3 text-sm text-destructive text-center">{error}</p>}
      </AuthCard>
    )
  }

  if (status === 'pick-project') {
    return (
      <AuthCard>
        <h1 className="mb-2 text-2xl font-bold text-foreground text-center">Select a project</h1>
        <p className="mb-5 text-sm text-muted-foreground text-center">
          Choose which project the CLI should start on. You can switch later with{' '}
          <code className="font-mono text-xs">conductor init</code>.
        </p>
        <div className="space-y-2">
          {projectChoices.map((p) => (
            <button
              key={p.id}
              onClick={() => handlePickProject(p)}
              className="w-full flex items-center justify-between rounded-md border border-border bg-background px-3 py-2.5 text-left hover:bg-muted"
            >
              <span className="text-sm font-medium text-foreground truncate">{p.name}</span>
              <span className="ml-3 shrink-0 text-xs text-primary">Use</span>
            </button>
          ))}
        </div>
        {error && <p className="mt-3 text-sm text-destructive text-center">{error}</p>}
      </AuthCard>
    )
  }

  return (
    <AuthCard>
      <h1 className="mb-2 text-2xl font-bold text-foreground text-center">Conductor CLI Login</h1>
      <p className="mb-8 text-sm text-muted-foreground text-center">
        Sign in to authenticate your terminal session.
      </p>
      <GoogleSignInButton onClick={handleSignIn} loading={status === 'loading'} />
      {(status === 'error') && error && <p className="mt-3 text-sm text-destructive text-center">{error}</p>}
      <AuthDivider className="my-4" />
      <EmailPasswordForm
        onSignIn={handleEmailSignIn}
        onSignUp={createEmailAccount}
        onResendVerification={resendVerificationEmail}
        onSendPasswordReset={sendPasswordReset}
      />
    </AuthCard>
  )
}

export default function CliLoginPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center bg-background text-muted-foreground">Loading...</div>}>
      <CliLoginContent />
    </Suspense>
  )
}
