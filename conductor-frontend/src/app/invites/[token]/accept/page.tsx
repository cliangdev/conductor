'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useRef, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { PartyPopper } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuth } from '@/contexts/AuthContext'
import { apiPost, apiErrorMessage, type ApiError } from '@/lib/api'

interface AcceptInviteResponse {
  projectId: string
  projectName: string
  role: string
}

type PageState =
  | { status: 'loading' }
  | { status: 'success'; projectName: string; projectId: string; role: string }
  | { status: 'error'; message: string }

function errorMessageForStatus(status: number): string {
  if (status === 410) return 'This invite has expired'
  if (status === 409) return 'This invite has already been used'
  if (status === 404) return 'Invite not found'
  return 'Something went wrong. Please try again.'
}

export default function AcceptInvitePage() {
  const params = useParams()
  const token = params.token as string
  const router = useRouter()
  const { user, accessToken, loading: authLoading } = useAuth()

  const [pageState, setPageState] = useState<PageState>({ status: 'loading' })
  const acceptedRef = useRef(false)

  useEffect(() => {
    if (authLoading) return

    if (!user || !accessToken) {
      router.replace(`/login?next=/invites/${token}/accept`)
      return
    }

    if (acceptedRef.current) return
    acceptedRef.current = true

    async function acceptInvite() {
      try {
        const result = await apiPost<AcceptInviteResponse>(
          `/api/v1/invites/${token}/accept`,
          {},
          accessToken!,
        )
        setPageState({
          status: 'success',
          projectName: result.projectName,
          projectId: result.projectId,
          role: result.role,
        })
        setTimeout(() => {
          router.push(`/app/projects/${result.projectId}`)
        }, 2000)
      } catch (err) {
        setPageState({
          status: 'error',
          message: apiErrorMessage(err, errorMessageForStatus((err as ApiError).status ?? 0)),
        })
      }
    }

    acceptInvite()
  }, [authLoading, user, accessToken, token, router])

  return (
    <div className="min-h-screen flex items-center justify-center bg-background px-4">
      <div className="max-w-md w-full text-center">
        {pageState.status === 'loading' && (
          <div className="flex flex-col items-center gap-3">
            <Skeleton className="h-12 w-12 rounded-lg" />
            <Skeleton className="h-4 w-48" />
            <p className="sr-only">Accepting your invite…</p>
          </div>
        )}

        {pageState.status === 'success' && (
          <div>
            <div className="flex items-center justify-center w-16 h-16 rounded-lg bg-muted mx-auto mb-6">
              <PartyPopper className="w-8 h-8 text-muted-foreground" />
            </div>
            <h1 className="text-2xl font-bold text-foreground">
              You&apos;ve joined {pageState.projectName}
            </h1>
            <p className="mt-2 text-muted-foreground">
              You&apos;ve been added as a{' '}
              <span className="font-medium text-foreground">{pageState.role.charAt(0) + pageState.role.slice(1).toLowerCase()}</span>
              . Redirecting you now…
            </p>
          </div>
        )}

        {pageState.status === 'error' && (
          <div>
            <h1 className="text-2xl font-bold text-foreground">Unable to accept invite</h1>
            <p className="mt-2 text-muted-foreground" role="alert">{pageState.message}</p>
            <Button className="mt-6" onClick={() => router.push('/app')}>
              Go to app
            </Button>
          </div>
        )}
      </div>
    </div>
  )
}
