'use client'

import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Alert } from '@/components/ui/alert'
import { authErrorMessage, isEmailNotVerifiedError } from '@/lib/auth-errors'

export const MIN_PASSWORD_LENGTH = 8
export const RESEND_COOLDOWN_SECONDS = 30

type View = 'signin' | 'create' | 'reset' | 'verify'

export interface EmailPasswordFormProps {
  /** Which form to open on. The login page passes `create` for `?mode=signup`. */
  initialMode?: 'signin' | 'create'
  /** Sign in and complete whatever the host page does next (navigate, mint a CLI key, ...). Throw on failure. */
  onSignIn: (email: string, password: string) => Promise<void>
  /** Create the account and send the verification email. Throw on failure. */
  onSignUp: (name: string, email: string, password: string) => Promise<void>
  onResendVerification: () => Promise<void>
  onSendPasswordReset: (email: string) => Promise<void>
}

const linkButton =
  'text-[13px] text-foreground-muted transition-colors hover:text-foreground hover:underline underline-offset-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring rounded-sm'

/**
 * Email + password sign-in / account creation, shared by the web login page and the CLI login page.
 * It owns the form state (modes, verify-email, reset) but not the session: the host supplies the
 * Firebase/backend callbacks, so a resolved `onSignIn` means "authenticated" and the host takes over.
 */
export function EmailPasswordForm({
  initialMode = 'signin',
  onSignIn,
  onSignUp,
  onResendVerification,
  onSendPasswordReset,
}: EmailPasswordFormProps) {
  const [view, setView] = useState<View>(initialMode)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [resetSent, setResetSent] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const cooling = cooldown > 0

  useEffect(() => {
    if (!cooling) return
    const timer = setInterval(() => setCooldown((c) => Math.max(0, c - 1)), 1000)
    return () => clearInterval(timer)
  }, [cooling])

  function go(next: View) {
    setView(next)
    setError(null)
    setNotice(null)
    setResetSent(false)
  }

  async function run(action: () => Promise<void>) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await action()
    } catch (err) {
      setError(authErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (view === 'signin') {
      void run(async () => {
        try {
          await onSignIn(email, password)
        } catch (err) {
          if (isEmailNotVerifiedError(err)) {
            setView('verify')
            return
          }
          throw err
        }
      })
    } else if (view === 'create') {
      void run(async () => {
        await onSignUp(name.trim(), email, password)
        setCooldown(RESEND_COOLDOWN_SECONDS)
        setView('verify')
      })
    } else if (view === 'reset') {
      void run(async () => {
        try {
          await onSendPasswordReset(email)
        } catch (err) {
          // Never reveal whether an account exists for this address.
          if ((err as { code?: string })?.code !== 'auth/user-not-found') throw err
        }
        setResetSent(true)
      })
    }
  }

  function handleResend() {
    void run(async () => {
      await onResendVerification()
      setCooldown(RESEND_COOLDOWN_SECONDS)
      setNotice('Verification email sent.')
    })
  }

  function handleVerified() {
    void run(async () => {
      try {
        await onSignIn(email, password)
      } catch (err) {
        if (isEmailNotVerifiedError(err)) {
          setError("We haven't seen your verification yet. Open the link in the email, then try again.")
          return
        }
        throw err
      }
    })
  }

  if (view === 'verify') {
    return (
      <div className="space-y-4">
        <div>
          <h2 className="text-sm font-semibold text-foreground">Check your inbox</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            We sent a link to <span className="font-medium text-foreground">{email}</span>. Open it to
            verify your email, then come back here.
          </p>
        </div>
        {error && <Alert variant="destructive">{error}</Alert>}
        {notice && (
          <p role="status" className="text-sm text-muted-foreground">
            {notice}
          </p>
        )}
        <div className="space-y-2">
          <Button type="button" className="w-full" size="lg" onClick={handleVerified} disabled={busy}>
            I&apos;ve verified my email
          </Button>
          <Button
            type="button"
            variant="outline"
            className="w-full"
            onClick={handleResend}
            disabled={busy || cooldown > 0}
          >
            {cooldown > 0 ? `Resend email (${cooldown}s)` : 'Resend email'}
          </Button>
        </div>
        <div className="text-center">
          <button type="button" className={linkButton} onClick={() => go('signin')}>
            Back to sign in
          </button>
        </div>
      </div>
    )
  }

  if (view === 'reset') {
    return (
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <h2 className="text-sm font-semibold text-foreground">Reset your password</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Enter your email and we&apos;ll send you a link to choose a new one.
          </p>
        </div>
        <div>
          <Label htmlFor="auth-email">Email</Label>
          <Input
            id="auth-email"
            type="email"
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        {error && <Alert variant="destructive">{error}</Alert>}
        {resetSent && (
          <p role="status" className="text-sm text-muted-foreground">
            If an account exists for that address, we&apos;ve sent a reset link.
          </p>
        )}
        <Button type="submit" className="w-full" size="lg" disabled={busy}>
          {busy ? 'Sending...' : 'Send reset link'}
        </Button>
        <div className="text-center">
          <button type="button" className={linkButton} onClick={() => go('signin')}>
            Back to sign in
          </button>
        </div>
      </form>
    )
  }

  const creating = view === 'create'

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {creating && (
        <div>
          <Label htmlFor="auth-name">Name</Label>
          <Input
            id="auth-name"
            type="text"
            autoComplete="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
          />
        </div>
      )}
      <div>
        <Label htmlFor="auth-email">Email</Label>
        <Input
          id="auth-email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
      </div>
      <div>
        <div className="flex items-baseline justify-between">
          <Label htmlFor="auth-password">Password</Label>
          {!creating && (
            <button type="button" className={linkButton} onClick={() => go('reset')}>
              Forgot password?
            </button>
          )}
        </div>
        <Input
          id="auth-password"
          type="password"
          autoComplete={creating ? 'new-password' : 'current-password'}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
          minLength={creating ? MIN_PASSWORD_LENGTH : undefined}
          aria-describedby={creating ? 'auth-password-hint' : undefined}
        />
        {creating && (
          <p id="auth-password-hint" className="mt-1 text-xs text-muted-foreground">
            At least {MIN_PASSWORD_LENGTH} characters.
          </p>
        )}
      </div>
      {error && <Alert variant="destructive">{error}</Alert>}
      <Button type="submit" className="w-full" size="lg" disabled={busy}>
        {creating
          ? busy
            ? 'Creating account...'
            : 'Create account'
          : busy
            ? 'Signing in...'
            : 'Sign in'}
      </Button>
      <p className="text-center">
        <button type="button" className={linkButton} onClick={() => go(creating ? 'signin' : 'create')}>
          {creating ? 'Already have an account? Sign in' : 'New to Conductor? Create an account'}
        </button>
      </p>
    </form>
  )
}
