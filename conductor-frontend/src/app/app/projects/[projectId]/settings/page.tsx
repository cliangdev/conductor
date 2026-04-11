'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet } from '@/lib/api'
import type { Member } from '@/types'
import { NotificationSettingsPage } from '@/components/notifications/NotificationSettingsPage'

interface ApiError extends Error {
  status?: number
}

export default function SettingsPage() {
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
      // Members fetch failure is non-fatal; role check will just show permission denied
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
      const apiErr = err as ApiError
      if (apiErr.status === 403 || (err as Error).message?.includes('403')) {
        setAccessDenied(true)
      } else if (apiErr.status !== undefined) {
        setSettingsError('Failed to load settings.')
      }
    } finally {
      setSettingsLoading(false)
    }
  }, [accessToken, projectId])

  useEffect(() => {
    fetchMembers()
  }, [fetchMembers])

  useEffect(() => {
    fetchSettingsAccess()
  }, [fetchSettingsAccess])

  const currentUserRole = members.find((m) => m.userId === user?.id)?.role
  const isAdmin = currentUserRole === 'ADMIN'

  if (membersLoading || settingsLoading) {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">Loading settings…</p>
      </div>
    )
  }

  if (!isAdmin) {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <h1 className="text-2xl font-bold text-foreground mb-6">Settings</h1>
        <p className="text-sm text-muted-foreground">
          You don&apos;t have permission to manage settings.
        </p>
      </div>
    )
  }

  if (accessDenied) {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <h1 className="text-2xl font-bold text-foreground mb-6">Settings</h1>
        <p className="text-sm text-destructive" role="alert">
          Access denied. You do not have permission to view project settings.
        </p>
      </div>
    )
  }

  if (settingsError) {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <h1 className="text-2xl font-bold text-foreground mb-6">Settings</h1>
        <p className="text-sm text-destructive" role="alert">{settingsError}</p>
      </div>
    )
  }

  return (
    <div className="max-w-4xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-foreground mb-6">Settings</h1>
      <NotificationSettingsPage projectId={projectId} accessToken={accessToken!} />
    </div>
  )
}
