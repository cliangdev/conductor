'use client'

export const dynamic = 'force-dynamic'

import { useRouter, useSearchParams } from 'next/navigation'
import { useEffect, useState, Suspense } from 'react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { AuthCard, GoogleSignInButton } from '@/components/auth/AuthCard'
import { useAuth } from '@/contexts/AuthContext'

function resolveNext(next: string | null): string {
  return next && next.startsWith('/') ? next : '/app/projects'
}

function Header() {
  return (
    <>
      <h1 className="mb-2 text-2xl font-bold text-foreground text-center">Conductor</h1>
      <p className="mb-8 text-sm text-muted-foreground text-center">Agentic software development</p>
    </>
  )
}

function LocalLoginForm() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { signIn } = useAuth()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await signIn({ email, password })
      router.push(resolveNext(searchParams.get('next')))
    } catch {
      setError('Invalid email or password')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthCard>
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

function GoogleLoginForm() {
  const { signIn, signInError } = useAuth()
  const [loading, setLoading] = useState(false)

  async function handleSignIn() {
    setLoading(true)
    try {
      await signIn()
      // On success the LoginForm effect detects user and navigates; leave loading true
    } catch {
      setLoading(false)
    }
  }

  return (
    <AuthCard>
      <Header />
      <GoogleSignInButton onClick={handleSignIn} loading={loading} />
      {signInError && <p className="mt-3 text-sm text-destructive text-center">{signInError}</p>}
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

  return isLocalMode ? <LocalLoginForm /> : <GoogleLoginForm />
}

export default function LoginPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center bg-background text-muted-foreground">Loading...</div>}>
      <LoginForm />
    </Suspense>
  )
}
