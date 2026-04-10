'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, apiPatch } from '@/lib/api'
import type { Member } from '@/types'

interface ProjectSettings {
  discordWebhookUrl: string | null
}

interface ApiError extends Error {
  status?: number
}

const DISCORD_WEBHOOK_PREFIX = 'https://discord.com/api/webhooks/'

export default function SettingsPage() {
  const params = useParams<{ projectId: string }>()
  const projectId = params.projectId
  const { accessToken, user } = useAuth()

  const [members, setMembers] = useState<Member[]>([])
  const [membersLoading, setMembersLoading] = useState(true)

  const [settings, setSettings] = useState<ProjectSettings | null>(null)
  const [settingsLoading, setSettingsLoading] = useState(true)
  const [settingsError, setSettingsError] = useState<string | null>(null)
  const [accessDenied, setAccessDenied] = useState(false)

  const [showInput, setShowInput] = useState(false)
  const [webhookUrl, setWebhookUrl] = useState('')
  const [urlError, setUrlError] = useState<string | null>(null)
  const [saveLoading, setSaveLoading] = useState(false)
  const [saveMessage, setSaveMessage] = useState<{ text: string; type: 'success' | 'error' } | null>(null)

  const [testLoading, setTestLoading] = useState(false)
  const [testStatus, setTestStatus] = useState<'idle' | 'success' | 'error'>('idle')

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

  const fetchSettings = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<ProjectSettings>(`/api/v1/projects/${projectId}/settings`, accessToken)
      setSettings(data)
      setSettingsError(null)
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 403 || (err as Error).message?.includes('403')) {
        setAccessDenied(true)
      } else {
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
    fetchSettings()
  }, [fetchSettings])

  const currentUserRole = members.find((m) => m.userId === user?.id)?.role
  const isAdmin = currentUserRole === 'ADMIN'

  const webhookConfigured = Boolean(settings?.discordWebhookUrl)

  function validateUrl(url: string): string | null {
    if (!url.trim()) return 'Webhook URL is required.'
    if (!url.startsWith(DISCORD_WEBHOOK_PREFIX)) {
      return `URL must start with ${DISCORD_WEBHOOK_PREFIX}`
    }
    return null
  }

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    const validationError = validateUrl(webhookUrl)
    if (validationError) {
      setUrlError(validationError)
      return
    }
    setUrlError(null)
    setSaveLoading(true)
    setSaveMessage(null)
    try {
      const updated = await apiPatch<ProjectSettings>(
        `/api/v1/projects/${projectId}/settings`,
        { discordWebhookUrl: webhookUrl.trim() },
        accessToken!,
      )
      setSettings(updated)
      setWebhookUrl('')
      setShowInput(false)
      setSaveMessage({ text: 'Webhook URL saved successfully.', type: 'success' })
      setTestStatus('idle')
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 400) {
        setSaveMessage({ text: 'Invalid webhook URL. Please check and try again.', type: 'error' })
      } else {
        setSaveMessage({ text: 'Failed to save settings. Please try again.', type: 'error' })
      }
    } finally {
      setSaveLoading(false)
    }
  }

  async function handleTestWebhook() {
    if (!accessToken) return
    setTestLoading(true)
    setTestStatus('idle')
    try {
      const res = await fetch(
        `${process.env.NEXT_PUBLIC_API_URL}/api/v1/projects/${projectId}/settings/test-discord`,
        {
          method: 'POST',
          headers: { Authorization: `Bearer ${accessToken}` },
        },
      )
      if (res.ok) {
        setTestStatus('success')
      } else {
        setTestStatus('error')
      }
    } catch {
      setTestStatus('error')
    } finally {
      setTestLoading(false)
    }
  }

  if (membersLoading || settingsLoading) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">Loading settings…</p>
      </div>
    )
  }

  if (!isAdmin) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <h1 className="text-2xl font-bold text-foreground mb-6">Settings</h1>
        <p className="text-sm text-muted-foreground">
          You don&apos;t have permission to manage settings.
        </p>
      </div>
    )
  }

  if (accessDenied) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <h1 className="text-2xl font-bold text-foreground mb-6">Settings</h1>
        <p className="text-sm text-destructive" role="alert">
          Access denied. You do not have permission to view project settings.
        </p>
      </div>
    )
  }

  if (settingsError) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <h1 className="text-2xl font-bold text-foreground mb-6">Settings</h1>
        <p className="text-sm text-destructive" role="alert">{settingsError}</p>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-foreground mb-6">Settings</h1>

      <section>
        <h2 className="text-base font-semibold text-foreground mb-4">Notifications</h2>

        <div className="rounded-lg border border-border bg-card p-6">
          <h3 className="text-sm font-semibold text-foreground mb-1">Discord Webhook</h3>
          <p className="text-sm text-muted-foreground mb-4">
            Receive notifications in Discord when review events occur.
          </p>

          {webhookConfigured && !showInput && (
            <div className="mb-4">
              <p className="text-sm text-muted-foreground mb-2">Current webhook URL:</p>
              <p className="text-sm text-foreground font-mono bg-muted px-3 py-2 rounded-md border border-border">
                {settings!.discordWebhookUrl}
              </p>
            </div>
          )}

          {(!webhookConfigured || showInput) && (
            <form onSubmit={handleSave} noValidate className="space-y-3">
              <div>
                <label
                  htmlFor="webhook-url"
                  className="block text-sm font-medium text-foreground mb-1"
                >
                  Webhook URL
                </label>
                <input
                  id="webhook-url"
                  type="url"
                  value={webhookUrl}
                  onChange={(e) => {
                    setWebhookUrl(e.target.value)
                    setUrlError(null)
                    setSaveMessage(null)
                  }}
                  placeholder="https://discord.com/api/webhooks/..."
                  className="border border-border bg-background text-foreground rounded-md px-3 py-2 text-sm w-full focus:outline-none focus:ring-2 focus:ring-ring"
                />
                {urlError && (
                  <p className="mt-1 text-sm text-destructive" role="alert">
                    {urlError}
                  </p>
                )}
              </div>

              {saveMessage && (
                <p
                  className={`text-sm ${saveMessage.type === 'success' ? 'text-green-600' : 'text-destructive'}`}
                  role="alert"
                >
                  {saveMessage.text}
                </p>
              )}

              <div className="flex items-center gap-3 pt-1">
                <button
                  type="submit"
                  disabled={saveLoading}
                  className="bg-primary text-primary-foreground hover:bg-primary/90 px-4 py-2 rounded-md text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {saveLoading ? 'Saving…' : 'Save'}
                </button>
                {showInput && (
                  <button
                    type="button"
                    onClick={() => {
                      setShowInput(false)
                      setWebhookUrl('')
                      setUrlError(null)
                      setSaveMessage(null)
                    }}
                    className="border border-border bg-background text-foreground hover:bg-muted px-4 py-2 rounded-md text-sm"
                  >
                    Cancel
                  </button>
                )}
              </div>
            </form>
          )}

          {webhookConfigured && !showInput && (
            <div className="flex items-center gap-3 mt-4">
              <button
                type="button"
                onClick={() => {
                  setShowInput(true)
                  setSaveMessage(null)
                  setTestStatus('idle')
                }}
                className="border border-border bg-background text-foreground hover:bg-muted px-4 py-2 rounded-md text-sm"
              >
                Change webhook URL
              </button>
              <button
                type="button"
                onClick={handleTestWebhook}
                disabled={testLoading}
                className="border border-border bg-background text-foreground hover:bg-muted px-4 py-2 rounded-md text-sm disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {testLoading ? 'Sending…' : 'Send test message'}
              </button>
            </div>
          )}

          {saveMessage && webhookConfigured && !showInput && (
            <p
              className={`mt-3 text-sm ${saveMessage.type === 'success' ? 'text-green-600' : 'text-destructive'}`}
              role="alert"
            >
              {saveMessage.text}
            </p>
          )}

          {testStatus === 'success' && (
            <p className="mt-3 text-sm text-green-600" role="status">
              Test message sent!
            </p>
          )}
          {testStatus === 'error' && (
            <p className="mt-3 text-sm text-destructive" role="alert">
              Webhook failed — check the URL.
            </p>
          )}
        </div>
      </section>
    </div>
  )
}
