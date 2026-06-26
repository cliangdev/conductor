'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, apiErrorMessage, type ApiError } from '@/lib/api'
import type { Member } from '@/types'
import { NotificationSettingsPage } from '@/components/notifications/NotificationSettingsPage'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'

export default function NotificationsPage() {
  const params = useParams<{ projectId: string }>()
  const projectId = params.projectId
  const { accessToken, user } = useAuth()

  const [members, setMembers] = useState<Member[]>([])
  const [membersLoading, setMembersLoading] = useState(true)
  const [accessDenied, setAccessDenied] = useState(false)
  const [settingsLoading, setSettingsLoading] = useState(true)
  const [settingsError, setSettingsError] = useState<string | null>(null)

  const fetchMembers = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, accessToken)
      setMembers(data)
    } catch {
      // non-fatal; role check will show permission denied
    } finally {
      setMembersLoading(false)
    }
  }, [accessToken, projectId])

  const fetchSettingsAccess = useCallback(async () => {
    if (!accessToken) return
    try {
      await apiGet(`/api/v1/projects/${projectId}/notifications/channels`, accessToken)
      setSettingsError(null)
    } catch (err) {
      if ((err as ApiError).status === 403) {
        setAccessDenied(true)
      } else {
        setSettingsError(apiErrorMessage(err, 'Failed to load settings.'))
      }
    } finally {
      setSettingsLoading(false)
    }
  }, [accessToken, projectId])

  useEffect(() => { fetchMembers() }, [fetchMembers])
  useEffect(() => { fetchSettingsAccess() }, [fetchSettingsAccess])

  const currentUserRole = members.find((m) => m.userId === user?.id)?.role
  const isAdmin = currentUserRole === 'ADMIN'

  const header = (
    <PageHeader
      breadcrumbs={[
        { label: 'Settings', href: `/app/projects/${projectId}/settings/general` },
        { label: 'Notifications' },
      ]}
      title="Notifications"
    />
  )

  if (membersLoading || settingsLoading) {
    return (
      <PageContainer>
        {header}
        <p className="text-sm text-muted-foreground">Loading…</p>
      </PageContainer>
    )
  }

  if (!isAdmin) {
    return (
      <PageContainer>
        {header}
        <p className="text-sm text-muted-foreground">
          You don&apos;t have permission to manage settings.
        </p>
      </PageContainer>
    )
  }

  if (accessDenied) {
    return (
      <PageContainer>
        {header}
        <p className="text-sm text-destructive" role="alert">
          Access denied. You do not have permission to view project settings.
        </p>
      </PageContainer>
    )
  }

  if (settingsError) {
    return (
      <PageContainer>
        {header}
        <p className="text-sm text-destructive" role="alert">{settingsError}</p>
      </PageContainer>
    )
  }

  return (
    <PageContainer>
      {header}
      <NotificationSettingsPage projectId={projectId} accessToken={accessToken!} />
    </PageContainer>
  )
}
