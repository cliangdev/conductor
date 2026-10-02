'use client'

export const dynamic = 'force-dynamic'

import Link from 'next/link'
import { useRouter, useSearchParams } from 'next/navigation'
import { useEffect, useState, Suspense } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { AuthCard, AuthDivider, GoogleSignInButton } from '@/components/auth/AuthCard'
import { EmailPasswordForm } from '@/components/auth/EmailPasswordForm'
import { useAuth } from '@/contexts/AuthContext'
import { REPO_URL } from '@/components/site/SiteChrome'
import { ConductorLogo } from '@/components/brand/ConductorLogo'

function resolveNext(next: string | null): string {
  return next && next.startsWith('/') ? next : '/app/projects'
}

function Header() {
  return (
    <>
      <h1 className="mb-2 flex justify-center">
        <ConductorLogo size="lg" />
        <span className="sr-only">Conductor</span>
      </h1>
      <p className="mb-8 text-sm text-muted-foreground text-center">
        Write it or let an agent draft it. Your team approves it.
      </p>
    </>
  )
}

/** Links out of the sign-in card. Someone who lands here first needs a way to the public pages,
 * and a platform app reviewer looks for the policy links from wherever they happen to be. */
function LoginFooter() {
  return (
    <nav className="flex items-center gap-4 text-[13px] text-muted-foreground">
      <Link href="/" className="transition-colors hover:text-foreground">
        Back to home
      </Link>
      <Link href="/privacy" className="transition-colors hover:text-foreground">
        Privacy
      </Link>
      <Link href="/terms" className="transition-colors hover:text-foreground">
        Terms
      </Link>
      <Link href="/data-deletion" className="transition-colors hover:text-foreground">
        Data Deletion
      </Link>
      <a
        href={REPO_URL}
        target="_blank"
        rel="noreferrer"
        className="transition-colors hover:text-foreground"
      >
        GitHub
      </a>
    </nav>
  )
}

function LocalLoginForm() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { signInWithEmail } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await signInWithEmail(email, password)
      router.push(resolveNext(searchParams.get('next')))
    } catch {
      setError('Invalid email or password')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthCard footer={<LoginFooter />}>
      <Header />
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <Label htmlFor="email">Email</Label>
          <Input
            id="email"
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        </div>
        <div>
          <Label htmlFor="password">Password</Label>
          <Input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </div>
        {error && <p className="text-sm text-destructive">{error}</p>}
        <Button type="submit" className="w-full" size="lg" disabled={loading}>
          {loading ? 'Signing in...' : 'Sign in'}
        </Button>
      </form>
    </AuthCard>
  )
}

function FirebaseLoginForm() {
  const searchParams = useSearchParams()
  const { signInWithGoogle, signInWithEmail, signUpWithEmail, resendVerificationEmail, sendPasswordReset, signInError } =
    useAuth()
  const [loading, setLoading] = useState(false)

  async function handleGoogle() {
    setLoading(true)
    try {
      await signInWithGoogle()
      // On success the LoginForm effect detects user and navigates; leave loading true
    } catch {
      setLoading(false)
    }
  }

  return (
    <AuthCard footer={<LoginFooter />}>
      <Header />
      <GoogleSignInButton onClick={handleGoogle} loading={loading} />
      {signInError && <p className="mt-3 text-sm text-destructive text-center">{signInError}</p>}
      <AuthDivider className="my-4" />
      <EmailPasswordForm
        initialMode={searchParams.get('mode') === 'signup' ? 'create' : 'signin'}
        onSignIn={signInWithEmail}
        onSignUp={signUpWithEmail}
        onResendVerification={resendVerificationEmail}
        onSendPasswordReset={sendPasswordReset}
      />
    </AuthCard>
  )
}

function LoginForm() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { user, loading } = useAuth()
  const isLocalMode = process.env.NEXT_PUBLIC_AUTH_MODE === 'local'

  useEffect(() => {
    if (!loading && user) {
      router.replace(resolveNext(searchParams.get('next')))
    }
  }, [loading, user, router, searchParams])

  if (!loading && user) return null

  return isLocalMode ? <LocalLoginForm /> : <FirebaseLoginForm />
}

export default function LoginPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center bg-background text-muted-foreground">Loading...</div>}>
      <LoginForm />
    </Suspense>
  )
}
