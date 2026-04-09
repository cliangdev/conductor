'use client'

export const dynamic = 'force-dynamic'

import { useRouter, useSearchParams } from 'next/navigation'
import { Suspense } from 'react'
import { Button } from '@/components/ui/button'
import { useAuth } from '@/contexts/AuthContext'

function LoginForm() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { signIn } = useAuth()

  async function handleSignIn() {
    await signIn()
    const next = searchParams.get('next') ?? '/app/projects'
    router.push(next)
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50">
      <div className="w-full max-w-sm rounded-xl border bg-white p-8 shadow-sm">
        <h1 className="mb-2 text-2xl font-bold text-gray-900">Conductor</h1>
        <p className="mb-8 text-sm text-gray-500">Team PRD collaboration platform</p>
        <Button onClick={handleSignIn} className="w-full" size="lg">
          Sign in with Google
        </Button>
      </div>
    </div>
  )
}

export default function LoginPage() {
  return (
    <Suspense fallback={<div className="flex min-h-screen items-center justify-center">Loading...</div>}>
      <LoginForm />
    </Suspense>
  )
}
