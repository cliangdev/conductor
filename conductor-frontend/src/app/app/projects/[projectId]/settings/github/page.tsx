'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, listProjectRepositories, addProjectRepository, updateProjectRepository, deleteProjectRepository, listWebhookEvents } from '@/lib/api'
import type { ProjectRepository, WebhookEventSummary } from '@/lib/api'
import type { Member } from '@/types'

interface ApiError extends Error {
  status?: number
}

function generateWebhookSecret(): string {
  return Array.from(crypto.getRandomValues(new Uint8Array(32)))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

export default function GitHubSettingsPage() {
  const params = useParams<{ projectId: string }>()
  const projectId = params.projectId
  const { accessToken, user } = useAuth()
  const { showToast } = useToast()

  const [members, setMembers] = useState<Member[]>([])
  const [membersLoading, setMembersLoading] = useState(true)

  const [repositories, setRepositories] = useState<ProjectRepository[]>([])
  const [reposLoading, setReposLoading] = useState(true)
  const [reposError, setReposError] = useState<string | null>(null)

  const [copied, setCopied] = useState(false)

  const [webhookEvents, setWebhookEvents] = useState<WebhookEventSummary[]>([])
  const [eventsLoading, setEventsLoading] = useState(true)

  const [addOpen, setAddOpen] = useState(false)
  const [addLabel, setAddLabel] = useState('')
  const [addRepoUrl, setAddRepoUrl] = useState('')
  const [addSecret, setAddSecret] = useState('')
  const [addSecretVisible, setAddSecretVisible] = useState(false)
  const [addError, setAddError] = useState<string | null>(null)
  const [addSubmitting, setAddSubmitting] = useState(false)

  const [editRepo, setEditRepo] = useState<ProjectRepository | null>(null)
  const [editLabel, setEditLabel] = useState('')
  const [editSecret, setEditSecret] = useState('')
  const [editSecretVisible, setEditSecretVisible] = useState(false)
  const [editError, setEditError] = useState<string | null>(null)
  const [editSubmitting, setEditSubmitting] = useState(false)

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

  const fetchRepositories = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await listProjectRepositories(projectId, accessToken)
      setRepositories(data)
      setReposError(null)
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 403) {
        setReposError('access_denied')
      } else {
        setReposError('Failed to load repositories.')
      }
    } finally {
      setReposLoading(false)
    }
  }, [accessToken, projectId])

  const fetchWebhookEvents = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await listWebhookEvents(projectId, accessToken)
      setWebhookEvents(data)
    } catch {
      // non-fatal
    } finally {
      setEventsLoading(false)
    }
  }, [accessToken, projectId])

  useEffect(() => { fetchMembers() }, [fetchMembers])
  useEffect(() => { fetchRepositories() }, [fetchRepositories])
  useEffect(() => { fetchWebhookEvents() }, [fetchWebhookEvents])

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

  async function handleDeleteRepo(repositoryId: string) {
    if (!accessToken) return
    try {
      await deleteProjectRepository(projectId, repositoryId, accessToken)
      setRepositories((prev) => prev.filter((r) => r.id !== repositoryId))
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 403) {
        showToast('You do not have permission to remove repositories.', 'error')
      } else {
        showToast('Failed to remove repository. Please try again.', 'error')
      }
    }
  }

  function openAddModal() {
    setAddLabel('')
    setAddRepoUrl('')
    setAddSecret('')
    setAddSecretVisible(false)
    setAddError(null)
    setAddOpen(true)
  }

  function closeAddModal() {
    setAddOpen(false)
  }

  async function handleAddSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken) return

    if (!addLabel.trim()) {
      setAddError('Label is required.')
      return
    }
    if (!addRepoUrl.trim()) {
      setAddError('Repository URL is required.')
      return
    }
    if (!addSecret.trim()) {
      setAddError('Webhook secret is required.')
      return
    }

    setAddSubmitting(true)
    setAddError(null)
    try {
      const created = await addProjectRepository(
        projectId,
        { label: addLabel.trim(), repoUrl: addRepoUrl.trim(), webhookSecret: addSecret.trim() },
        accessToken,
      )
      setRepositories((prev) => [...prev, created])
      closeAddModal()
      showToast('Repository added.')
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 403) {
        setAddError('You do not have permission to add repositories.')
      } else if (apiErr.status === 409) {
        setAddError('This repository is already registered.')
      } else {
        setAddError('Failed to add repository. Please try again.')
      }
    } finally {
      setAddSubmitting(false)
    }
  }

  function openEditModal(repo: ProjectRepository) {
    setEditRepo(repo)
    setEditLabel(repo.label)
    setEditSecret('')
    setEditSecretVisible(false)
    setEditError(null)
  }

  function closeEditModal() {
    setEditRepo(null)
  }

  async function handleEditSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken || !editRepo) return

    if (!editLabel.trim()) {
      setEditError('Label is required.')
      return
    }

    setEditSubmitting(true)
    setEditError(null)
    try {
      const updated = await updateProjectRepository(
        projectId,
        editRepo.id,
        {
          label: editLabel.trim(),
          ...(editSecret.trim() ? { webhookSecret: editSecret.trim() } : {}),
        },
        accessToken,
      )
      setRepositories((prev) => prev.map((r) => (r.id === updated.id ? updated : r)))
      closeEditModal()
      showToast('Repository updated.')
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 403) {
        setEditError('You do not have permission to edit repositories.')
      } else {
        setEditError('Failed to update repository. Please try again.')
      }
    } finally {
      setEditSubmitting(false)
    }
  }

  if (membersLoading || reposLoading) {
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

  if (reposError === 'access_denied') {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-destructive" role="alert">
          Access denied. You do not have permission to view project settings.
        </p>
      </div>
    )
  }

  if (reposError) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-destructive" role="alert">{reposError}</p>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <h1 className="text-2xl font-bold text-foreground mb-6">GitHub Integration</h1>

      {/* Webhook URL section */}
      <div className="bg-card rounded-lg border border-border p-6 mb-6">
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
          Enter this URL in your GitHub repository&apos;s webhook settings. Make sure to select{' '}
          <strong>Pull requests</strong> under &ldquo;Let me select individual events&rdquo; so PR merges trigger status updates.
        </p>
      </div>

      {/* Repositories list */}
      <div className="bg-card rounded-lg border border-border p-6">
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-base font-semibold text-foreground">Repositories</h2>
          <Button type="button" size="sm" onClick={openAddModal}>
            Add Repository
          </Button>
        </div>

        {repositories.length === 0 ? (
          <p className="text-sm text-muted-foreground">No repositories registered yet.</p>
        ) : (
          <div className="divide-y divide-border">
            {repositories.map((repo) => (
              <div key={repo.id} className="flex items-center justify-between py-3 first:pt-0 last:pb-0">
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-medium text-foreground truncate">{repo.label}</p>
                  <a
                    href={repo.repoUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-xs text-muted-foreground hover:text-foreground truncate block"
                  >
                    {repo.repoUrl}
                  </a>
                </div>
                <div className="flex items-center gap-3 ml-4 shrink-0">
                  {repo.webhookSecretConfigured ? (
                    <span className="flex items-center gap-1 text-xs text-green-600 dark:text-green-400">
                      <span aria-hidden="true">✓</span> Configured
                    </span>
                  ) : (
                    <span className="text-xs text-muted-foreground">— Not configured</span>
                  )}
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    aria-label={`Edit ${repo.label}`}
                    onClick={() => openEditModal(repo)}
                  >
                    Edit
                  </Button>
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    aria-label={`Delete ${repo.label}`}
                    onClick={() => handleDeleteRepo(repo.id)}
                    className="text-destructive hover:text-destructive hover:bg-destructive/10"
                  >
                    Delete
                  </Button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Recent Webhook Events */}
      <div className="bg-card rounded-lg border border-border p-6 mt-6">
        <h2 className="text-base font-semibold text-foreground mb-4">Recent Webhook Events</h2>
        {eventsLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : webhookEvents.length === 0 ? (
          <p className="text-sm text-muted-foreground">No webhook events received yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs text-muted-foreground border-b border-border">
                  <th className="pb-2 pr-4 font-medium">Time</th>
                  <th className="pb-2 pr-4 font-medium">Event type</th>
                  <th className="pb-2 pr-4 font-medium">Status</th>
                  <th className="pb-2 font-medium">Details</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {webhookEvents.map((event) => (
                  <tr key={event.id}>
                    <td className="py-2 pr-4 text-muted-foreground whitespace-nowrap">
                      {new Date(event.createdAt).toLocaleString()}
                    </td>
                    <td className="py-2 pr-4 font-mono">{event.eventType}</td>
                    <td className="py-2 pr-4">
                      <span className={
                        event.status === 'PROCESSED' ? 'text-green-600 dark:text-green-400' :
                        event.status === 'PENDING' ? 'text-yellow-600 dark:text-yellow-400' :
                        'text-destructive'
                      }>
                        {event.status}
                      </span>
                    </td>
                    <td className="py-2 text-muted-foreground text-xs">
                      {event.errorMessage ?? (event.status === 'PROCESSED' ? '—' : '')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Edit Repository modal */}
      <Modal
        open={editRepo !== null}
        onOpenChange={(open) => { if (!open) closeEditModal() }}
        title="Edit Repository"
        description="Update the label or rotate the webhook secret."
      >
        <form onSubmit={handleEditSubmit} noValidate className="space-y-4">
          <div>
            <label htmlFor="edit-repo-label" className="block text-sm font-medium text-foreground mb-1">
              Label <span className="text-destructive">*</span>
            </label>
            <input
              id="edit-repo-label"
              type="text"
              value={editLabel}
              onChange={(e) => setEditLabel(e.target.value)}
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
            />
          </div>

          <div>
            <label htmlFor="edit-repo-url" className="block text-sm font-medium text-foreground mb-1">
              GitHub Repository URL
            </label>
            <input
              id="edit-repo-url"
              type="text"
              readOnly
              value={editRepo?.repoUrl ?? ''}
              className="w-full rounded-md border border-input bg-muted text-muted-foreground px-3 py-2 text-sm focus:outline-none"
            />
            <p className="mt-1 text-xs text-muted-foreground">To change the repository, delete this entry and add a new one.</p>
          </div>

          <div>
            <label htmlFor="edit-repo-secret" className="block text-sm font-medium text-foreground mb-1">
              New Webhook Secret <span className="text-muted-foreground font-normal">(leave blank to keep existing)</span>
            </label>
            <div className="flex items-center gap-2">
              <input
                id="edit-repo-secret"
                type={editSecretVisible ? 'text' : 'password'}
                value={editSecret}
                onChange={(e) => setEditSecret(e.target.value)}
                placeholder="Enter new secret to rotate"
                className="flex-1 rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent font-mono"
              />
              {editSecret && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setEditSecretVisible((v) => !v)}
                  aria-label={editSecretVisible ? 'Hide secret' : 'Show secret'}
                >
                  {editSecretVisible ? 'Hide' : 'Show'}
                </Button>
              )}
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => { setEditSecret(generateWebhookSecret()); setEditSecretVisible(true) }}
              >
                Generate
              </Button>
            </div>
          </div>

          {editError && (
            <p className="text-sm text-destructive" role="alert">{editError}</p>
          )}

          <div className="flex gap-3 pt-2">
            <Button type="submit" disabled={editSubmitting}>
              {editSubmitting ? 'Saving…' : 'Save'}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={closeEditModal}
              disabled={editSubmitting}
            >
              Cancel
            </Button>
          </div>
        </form>
      </Modal>

      {/* Add Repository modal */}
      <Modal
        open={addOpen}
        onOpenChange={(open) => { if (!open) closeAddModal() }}
        title="Add Repository"
        description="Register a GitHub repository to receive webhook events."
      >
        <form onSubmit={handleAddSubmit} noValidate className="space-y-4">
          <div>
            <label htmlFor="add-repo-label" className="block text-sm font-medium text-foreground mb-1">
              Label <span className="text-destructive">*</span>
            </label>
            <input
              id="add-repo-label"
              type="text"
              value={addLabel}
              onChange={(e) => setAddLabel(e.target.value)}
              placeholder="e.g. Frontend"
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
            />
          </div>

          <div>
            <label htmlFor="add-repo-url" className="block text-sm font-medium text-foreground mb-1">
              GitHub Repository URL <span className="text-destructive">*</span>
            </label>
            <input
              id="add-repo-url"
              type="text"
              value={addRepoUrl}
              onChange={(e) => setAddRepoUrl(e.target.value)}
              placeholder="https://github.com/org/repo"
              className="w-full rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent"
            />
          </div>

          <div>
            <label htmlFor="add-repo-secret" className="block text-sm font-medium text-foreground mb-1">
              Webhook Secret <span className="text-destructive">*</span>
            </label>
            <div className="flex items-center gap-2">
              <input
                id="add-repo-secret"
                type={addSecretVisible ? 'text' : 'password'}
                value={addSecret}
                onChange={(e) => setAddSecret(e.target.value)}
                placeholder="Enter webhook secret"
                className="flex-1 rounded-md border border-input bg-background text-foreground px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-ring focus:border-transparent font-mono"
              />
              {addSecret && (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setAddSecretVisible((v) => !v)}
                  aria-label={addSecretVisible ? 'Hide secret' : 'Show secret'}
                >
                  {addSecretVisible ? 'Hide' : 'Show'}
                </Button>
              )}
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => { setAddSecret(generateWebhookSecret()); setAddSecretVisible(true) }}
              >
                Generate
              </Button>
            </div>
          </div>

          {addError && (
            <p className="text-sm text-destructive" role="alert">{addError}</p>
          )}

          <div className="flex gap-3 pt-2">
            <Button type="submit" disabled={addSubmitting}>
              {addSubmitting ? 'Saving…' : 'Save'}
            </Button>
            <Button
              type="button"
              variant="outline"
              onClick={closeAddModal}
              disabled={addSubmitting}
            >
              Cancel
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  )
}
