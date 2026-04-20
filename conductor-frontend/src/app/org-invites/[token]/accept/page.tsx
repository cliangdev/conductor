'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useRef, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiPost } from '@/lib/api'

interface AcceptOrgInviteResponse {
  orgId: string
  orgName: string
  role: string
}

type PageState =
  | { status: 'loading' }
  | { status: 'success'; orgName: string; role: string }
  | { status: 'error'; message: string }

function errorMessageForStatus(status: number): string {
  if (status === 410) return 'This invite has expired'
  if (status === 409) return 'This invite has already been used'
  if (status === 404) return 'Invite not found'
  return 'Something went wrong. Please try again.'
}

interface ApiError extends Error {
  status?: number
}

export default function AcceptOrgInvitePage() {
  const params = useParams()
  const token = params.token as string
  const router = useRouter()
  const { user, accessToken, loading: authLoading } = useAuth()

  const [pageState, setPageState] = useState<PageState>({ status: 'loading' })
  const acceptedRef = useRef(false)

  useEffect(() => {
    if (authLoading) return

    if (!user || !accessToken) {
      router.replace(`/login?next=/org-invites/${token}/accept`)
      return
    }

    if (acceptedRef.current) return
    acceptedRef.current = true

    async function acceptInvite() {
      try {
        const result = await apiPost<AcceptOrgInviteResponse>(
          `/api/v1/org-invites/${token}/accept`,
          {},
          accessToken!,
        )
        setPageState({
          status: 'success',
          orgName: result.orgName,
          role: result.role,
        })
        setTimeout(() => {
          router.push('/app')
        }, 2000)
      } catch (err) {
        const apiErr = err as ApiError
        setPageState({
          status: 'error',
          message: errorMessageForStatus(apiErr.status ?? 0),
        })
      }
    }

    acceptInvite()
  }, [authLoading, user, accessToken, token, router])

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div className="max-w-md w-full text-center">
        {pageState.status === 'loading' && (
          <div>
            <p className="text-gray-600 text-lg">Accepting your invite…</p>
          </div>
        )}

        {pageState.status === 'success' && (
          <div>
            <div className="text-4xl mb-4">🎉</div>
            <h1 className="text-2xl font-bold text-gray-900">
              You&apos;ve joined {pageState.orgName}
            </h1>
            <p className="mt-2 text-gray-500">
              You&apos;ve been added as a{' '}
              <span className="font-medium">{pageState.role.charAt(0) + pageState.role.slice(1).toLowerCase()}</span>
              . Redirecting you now…
            </p>
          </div>
        )}

        {pageState.status === 'error' && (
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Unable to accept invite</h1>
            <p className="mt-2 text-gray-500" role="alert">{pageState.message}</p>
            <button
              onClick={() => router.push('/app')}
              className="mt-6 inline-flex items-center px-4 py-2 rounded-md bg-primary text-white text-sm font-medium hover:bg-primary/90 transition-colors"
            >
              Go to app
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
