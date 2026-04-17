'use client'

import { useEffect } from 'react'
import { useRouter } from 'next/navigation'
import { useOrg } from '@/contexts/OrgContext'

export function OrgGate({ children }: { children: React.ReactNode }) {
  const { needsOnboarding, loading } = useOrg()
  const router = useRouter()

  useEffect(() => {
    if (!loading && needsOnboarding) {
      router.replace('/onboarding')
    }
  }, [loading, needsOnboarding, router])

  if (loading || needsOnboarding) {
    return (
      <div className="flex h-screen items-center justify-center bg-background text-muted-foreground">
        Loading...
      </div>
    )
  }

  return <>{children}</>
}
