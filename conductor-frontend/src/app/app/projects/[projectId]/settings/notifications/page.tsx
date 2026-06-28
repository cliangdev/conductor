'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { apiGet, apiErrorMessage, type ApiError } from '@/lib/api'
import { NotificationSettingsPage } from '@/components/notifications/NotificationSettingsPage'
import { PageHeader } from '@/components/layout/PageHeader'

export default function NotificationsPage() {
  const params = useParams<{ projectId: string }>()
  const projectId = params.projectId
  const { accessToken } = useAuth()
  const { can, loading: roleLoading } = usePermissions()

  const [accessDenied, setAccessDenied] = useState(false)
  const [settingsLoading, setSettingsLoading] = useState(true)
  const [settingsError, setSettingsError] = useState<string | null>(null)

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

  useEffect(() => { fetchSettingsAccess() }, [fetchSettingsAccess])

  const isAdmin = can('notifications.manage')

  const header = <PageHeader title="Notifications" />

  if (roleLoading || settingsLoading) {
    return (
      <>
        {header}
        <p className="text-sm text-muted-foreground">Loading…</p>
      </>
    )
  }

  if (!isAdmin) {
    return (
      <>
        {header}
        <p className="text-sm text-muted-foreground">
          You don&apos;t have permission to manage settings.
        </p>
      </>
    )
  }

  if (accessDenied) {
    return (
      <>
        {header}
        <p className="text-sm text-destructive" role="alert">
          Access denied. You do not have permission to view project settings.
        </p>
      </>
    )
  }

  if (settingsError) {
    return (
      <>
        {header}
        <p className="text-sm text-destructive" role="alert">{settingsError}</p>
      </>
    )
  }

  return (
    <>
      {header}
      <NotificationSettingsPage projectId={projectId} accessToken={accessToken!} />
    </>
  )
}
