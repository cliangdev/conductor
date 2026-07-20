'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { LockIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { useToast } from '@/components/ui/toast'
import { Button, buttonVariants } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { Alert } from '@/components/ui/alert'
import { AgentAvatar, isAvatarColorToken } from '@/components/agents/AgentAvatar'
import {
  listKnowledgeDomains,
  updateKnowledgeDomain,
  createKnowledgeDomainSpecialist,
  LIBRARIAN_FALLBACK_AVATAR,
  type KnowledgeDomainDto,
} from '@/lib/knowledge-api'
import { listAgents, apiErrorMessage, type Agent } from '@/lib/api'
import { cn } from '@/lib/utils'

export const dynamic = 'force-dynamic'


/** Admin-only registry surface for the areas the librarian files into: approve/dismiss suggested
 *  areas (gap reports), see who owns each active area, and jump to its filing rules. Moved here
 *  from the Knowledge home page (formerly KnowledgeDomainsPanel) so the reading surface stays
 *  content-only and configuration lives behind one door. */
export default function KnowledgeManagePage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const { can } = usePermissions()
  const { showToast } = useToast()
  const isAdmin = can('workspace.manage')

  const [loading, setLoading] = useState(true)
  const [domains, setDomains] = useState<KnowledgeDomainDto[] | null>(null)
  const [agentsBySlug, setAgentsBySlug] = useState<Map<string, Agent>>(new Map())
  const [error, setError] = useState<string | null>(null)
  const [busySlug, setBusySlug] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!accessToken) return
    const [domainRows, agents] = await Promise.all([
      listKnowledgeDomains(projectId, accessToken),
      listAgents(projectId, accessToken),
    ])
    setDomains(domainRows)
    setAgentsBySlug(new Map(agents.map((a) => [a.slug, a])))
  }, [projectId, accessToken])

  useEffect(() => {
    if (!accessToken || !isAdmin) {
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    load()
      .catch((err) => {
        if (!cancelled) setError(apiErrorMessage(err, 'Failed to load knowledge areas'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [accessToken, isAdmin, load])

  async function refetchQuietly() {
    try {
      await load()
    } catch {
      // Keep showing the last-known rows rather than blanking the page over a refresh hiccup.
    }
  }

  async function handleApprove(slug: string) {
    if (!accessToken) return
    setBusySlug(slug)
    try {
      await updateKnowledgeDomain(projectId, slug, { state: 'ACTIVE' }, accessToken)
      showToast(`Area "${slug}" approved`)
      await refetchQuietly()
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to approve area'), 'error')
    } finally {
      setBusySlug(null)
    }
  }

  async function handleDismiss(slug: string) {
    if (!accessToken) return
    setBusySlug(slug)
    try {
      await updateKnowledgeDomain(projectId, slug, { state: 'DISMISSED' }, accessToken)
      showToast(`Area "${slug}" dismissed`)
      await refetchQuietly()
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to dismiss area'), 'error')
    } finally {
      setBusySlug(null)
    }
  }

  async function handleAssignSpecialist(slug: string) {
    if (!accessToken) return
    setBusySlug(slug)
    try {
      await createKnowledgeDomainSpecialist(projectId, slug, accessToken)
      showToast(`Specialist agent assigned to "${slug}"`)
      await refetchQuietly()
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to assign specialist'), 'error')
    } finally {
      setBusySlug(null)
    }
  }

  if (!isAdmin) {
    return (
      <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
        <div className="max-w-[45rem] mx-auto">
          <EmptyState
            icon={LockIcon}
            title="Admins only"
            description="Only workspace admins can manage knowledge areas and filing rules."
          />
        </div>
      </div>
    )
  }

  const active = (domains ?? []).filter((d) => d.state === 'ACTIVE')
  const suggested = (domains ?? []).filter((d) => d.state === 'SUGGESTED')

  return (
    <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
      <div className="max-w-[45rem] mx-auto space-y-6">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Manage knowledge</h1>
          <p className="text-sm text-muted-foreground mt-1">
            Areas, filing rules, and the agents that maintain them
          </p>
        </div>

        {error ? (
          <Alert variant="destructive">{error}</Alert>
        ) : loading ? (
          <div className="space-y-1">
            {[0, 1, 2].map((i) => (
              <Skeleton key={i} className="h-[52px] w-full" />
            ))}
          </div>
        ) : (
          <>
            {suggested.length > 0 && (
              <div className="space-y-2">
                <h2 className="text-[11.5px] font-semibold uppercase tracking-wider text-muted-foreground">
                  Suggested
                </h2>
                <div className="space-y-2">
                  {suggested.map((domain) => (
                    <Card key={domain.slug} className="p-4">
                      <p className="font-medium text-foreground">
                        The librarian suggests a new area: &quot;{domain.displayName}&quot;
                      </p>
                      {domain.suggestionReason && (
                        <p className="text-sm text-foreground-subtle mt-1">&ldquo;{domain.suggestionReason}&rdquo;</p>
                      )}
                      <p className="text-sm text-muted-foreground mt-1">
                        Approving creates {domain.pathPrefix} with a starter filing schema.
                      </p>
                      <div className="flex items-center gap-2 mt-3">
                        <Button
                          variant="success"
                          size="sm"
                          onClick={() => handleApprove(domain.slug)}
                          disabled={busySlug === domain.slug}
                        >
                          Approve
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => handleDismiss(domain.slug)}
                          disabled={busySlug === domain.slug}
                        >
                          Dismiss
                        </Button>
                      </div>
                    </Card>
                  ))}
                </div>
              </div>
            )}

            <div className="space-y-2">
              <h2 className="text-[11.5px] font-semibold uppercase tracking-wider text-muted-foreground">Areas</h2>
              {active.length === 0 ? (
                <p className="text-sm text-muted-foreground">No areas yet.</p>
              ) : (
                <Card className="divide-y divide-border">
                  {active.map((domain) => {
                    const owningAgent = domain.owningAgentSlug ? agentsBySlug.get(domain.owningAgentSlug) : undefined
                    const avatarEmoji = owningAgent?.avatarEmoji ?? LIBRARIAN_FALLBACK_AVATAR.emoji
                    const avatarColor = isAvatarColorToken(owningAgent?.avatarColor)
                      ? owningAgent.avatarColor
                      : LIBRARIAN_FALLBACK_AVATAR.color
                    const agentName = owningAgent?.name ?? 'Librarian'

                    return (
                      <div key={domain.slug} className="flex items-center gap-3 px-4 py-3 text-[13px]">
                        <div className="min-w-0 flex-1">
                          <div className="font-medium text-foreground">{domain.displayName}</div>
                          {domain.sourceTypePatterns.length > 0 && (
                            <div className="text-foreground-subtle mt-0.5 truncate">
                              routes {domain.sourceTypePatterns.join(', ')}
                            </div>
                          )}
                        </div>

                        <span className="flex items-center gap-1.5 text-foreground-subtle shrink-0">
                          <AgentAvatar emoji={avatarEmoji} color={avatarColor} size="sm" />
                          {agentName}
                        </span>

                        {domain.pendingCount > 0 && (
                          <Link
                            href={`/app/projects/${projectId}/knowledge/activity?tab=inbox&domain=${encodeURIComponent(domain.slug)}`}
                            className="text-foreground-subtle hover:text-foreground hover:underline shrink-0"
                          >
                            {domain.pendingCount} waiting
                          </Link>
                        )}

                        <div className="flex items-center gap-2 shrink-0">
                          {!domain.owningAgentSlug && (
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={busySlug === domain.slug}
                              onClick={() => handleAssignSpecialist(domain.slug)}
                            >
                              Assign specialist
                            </Button>
                          )}
                          <Link
                            href={`/app/projects/${projectId}/knowledge/page?path=${encodeURIComponent(domain.schemaPagePath)}`}
                            className={cn(buttonVariants({ variant: 'ghost', size: 'sm' }))}
                          >
                            Filing rules
                          </Link>
                        </div>
                      </div>
                    )
                  })}
                </Card>
              )}
            </div>

            <p className="text-xs text-foreground-subtle">
              Every area is maintained by the Librarian until you assign a specialist. Filing rules are the pages
              agents follow when writing here.
            </p>
          </>
        )}
      </div>
    </div>
  )
}
