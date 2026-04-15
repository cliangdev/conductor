'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, apiPatch } from '@/lib/api'
import type { Member } from '@/types'

interface ProjectSettings {
  githubWebhookConfigured: boolean
  githubRepoUrl: string | null
}

interface ApiError extends Error {
  status?: number
}

export default function GitHubSettingsPage() {
  const params = useParams<{ projectId: string }>()
  const projectId = params.projectId
  const { accessToken, user } = useAuth()
  const { showToast } = useToast()

  const [members, setMembers] = useState<Member[]>([])
  const [membersLoading, setMembersLoading] = useState(true)

  const [settings, setSettings] = useState<ProjectSettings | null>(null)
  const [settingsLoading, setSettingsLoading] = useState(true)
  const [settingsError, setSettingsError] = useState<string | null>(null)

  const [repoUrl, setRepoUrl] = useState('')
  const [webhookSecret, setWebhookSecret] = useState('')
  const [saving, setSaving] = useState(false)

  const [copied, setCopied] = useState(false)

  const webhookEndpointUrl = `${process.env.NEXT_PUBLIC_API_URL}/api/v1/projects/${projectId}/github/webhook`

  const fetchMembers = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, accessToken)
      setMembers(data)
    } catch {
      // non-fatal
    } finally {
      setMembersLoading(false)
    }
  }, [accessToken, projectId])

  const fetchSettings = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<ProjectSettings>(`/api/v1/projects/${projectId}/settings`, accessToken)
      setSettings(data)
      setRepoUrl(data.githubRepoUrl ?? '')
      setSettingsError(null)
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 403) {
        setSettingsError('access_denied')
      } else {
        setSettingsError('Failed to load settings.')
      }
    } finally {
      setSettingsLoading(false)
    }
  }, [accessToken, projectId])

  useEffect(() => { fetchMembers() }, [fetchMembers])
  useEffect(() => { fetchSettings() }, [fetchSettings])

  const currentUserRole = members.find((m) => m.userId === user?.id)?.role
  const isAdmin = currentUserRole === 'ADMIN'

  async function handleCopyWebhookUrl() {
    try {
      await navigator.clipboard.writeText(webhookEndpointUrl)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      showToast('Failed to copy to clipboard', 'error')
    }
  }

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken) return

    setSaving(true)
    try {
      const body: Record<string, string> = {
        githubRepoUrl: repoUrl.trim(),
      }
      if (webhookSecret.trim()) {
        body.githubWebhookSecret = webhookSecret.trim()
      }

      const updated = await apiPatch<ProjectSettings>(
        `/api/v1/projects/${projectId}/settings`,
        body,
        accessToken
      )
      setSettings(updated)
      setRepoUrl(updated.githubRepoUrl ?? '')
      setWebhookSecret('')
      showToast('GitHub settings saved.')
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 403) {
        showToast('You do not have permission to update settings.', 'error')
      } else {
        showToast('Failed to save settings. Please try again.', 'error')
      }
    } finally {
      setSaving(false)
    }
  }

  if (membersLoading || settingsLoading) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">Loading…</p>
      </div>
    )
  }

  if (!isAdmin) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">
          You don&apos;t have permission to manage settings.
        </p>
      </div>
    )
  }

  if (settingsError === 'access_denied') {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-destructive" role="alert">
          Access denied. You do not have permission to view project settings.
        </p>
      </div>
    )
  }

  if (settingsError) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-destructive" role="alert">{settingsError}</p>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-foreground mb-6">GitHub Integration</h1>

      <div className="bg-card rounded-lg border border-border p-6">
        {/* Webhook endpoint URL */}
        <div className="mb-6">
          <label className="block text-sm font-medium text-foreground mb-1">
            Webhook URL
          </label>
          <div className="flex items-center gap-2">
            <input
              readOnly
              value={webhookEndpointUrl}
              className="flex-1 rounded-md border border-input bg-muted text-foreground px-3 py-2 text-sm font-mono focus:outline-none"
              onFocus={(e) => e.target.select()}
            />
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleCopyWebhookUrl}
            >
              {copied ? 'Copied!' : 'Copy'}
            </Button>
          </div>
          <p className="mt-1 text-xs text-muted-foreground">
            Enter this URL in your GitHub repository&apos;s webhook settings
          </p>
        </div>

        <form onSubmit={handleSave} noValidate className="space-y-5">
          {/* Repository URL */}
          <div>
            <label htmlFor="github-repo-url" className="block text-sm font-medium text-foreground mb-1">
              Repository URL
            </label>
            <input
              id="github-repo-url"
              type="url"
              value={repoUrl}
              onChange={(e) => setRepoUrl(e.target.value)}
              placeholder="https://github.com/org/repo"
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
            />
          </div>

          {/* Webhook Secret */}
          <div>
            <label htmlFor="github-webhook-secret" className="block text-sm font-medium text-foreground mb-1">
              Webhook Secret
            </label>
            <input
              id="github-webhook-secret"
              type="password"
              value={webhookSecret}
              onChange={(e) => setWebhookSecret(e.target.value)}
              placeholder={
                settings?.githubWebhookConfigured
                  ? '••••••• configured'
                  : 'Enter webhook secret'
              }
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
            />
            {settings?.githubWebhookConfigured && !webhookSecret && (
              <p className="mt-1 text-xs text-muted-foreground">
                A webhook secret is already configured. Enter a new value to replace it.
              </p>
            )}
            <p className="mt-1 text-xs text-muted-foreground">
              Tip: generate a secret with{' '}
              <code className="font-mono bg-muted px-1 py-0.5 rounded">openssl rand -hex 32</code>{' '}
              and paste the same value in GitHub under{' '}
              <span className="font-medium">Repository → Settings → Webhooks → Secret</span>.
            </p>
          </div>

          <div className="pt-1">
            <Button type="submit" disabled={saving}>
              {saving ? 'Saving…' : 'Save'}
            </Button>
          </div>
        </form>
      </div>
    </div>
  )
}
