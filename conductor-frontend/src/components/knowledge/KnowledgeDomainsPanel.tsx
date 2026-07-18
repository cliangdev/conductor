'use client'

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { usePermissions } from '@/contexts/PermissionsContext'
import { useToast } from '@/components/ui/toast'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { AgentAvatar, isAvatarColorToken, type AvatarColorToken } from '@/components/agents/AgentAvatar'
import { statusHueClasses } from '@/components/ui/status-badge'
import {
  listKnowledgeDomains,
  updateKnowledgeDomain,
  createKnowledgeDomainSpecialist,
  type KnowledgeDomainDto,
} from '@/lib/knowledge-api'
import { listAgents, apiErrorMessage, type Agent } from '@/lib/api'
import { cn } from '@/lib/utils'

const LIBRARIAN_AVATAR_EMOJI = '📚'
const LIBRARIAN_AVATAR_COLOR: AvatarColorToken = 'violet'
const amber = statusHueClasses('amber')

/**
 * Domains overview — sibling card to KnowledgePipelineStrip on the Knowledge index page, same chrome
 * and best-effort behavior (a fetch failure renders nothing rather than an error, since this is
 * auxiliary to the wiki page it sits under). ACTIVE domains show their schema page, owning agent (or
 * "Librarian" as the generalist default), and live pending/processed counts; SUGGESTED domains (gap
 * reports raised by the librarian) show the reason with admin Approve/Dismiss actions. DISMISSED
 * domains are omitted entirely — already declined, nothing actionable to show.
 */
export function KnowledgeDomainsPanel({ projectId, token }: { projectId: string; token: string }) {
  const { can } = usePermissions()
  const { showToast } = useToast()
  const isAdmin = can('workspace.manage')

  const [loading, setLoading] = useState(true)
  const [domains, setDomains] = useState<KnowledgeDomainDto[] | null>(null)
  const [agentsBySlug, setAgentsBySlug] = useState<Map<string, Agent>>(new Map())
  const [busySlug, setBusySlug] = useState<string | null>(null)

  const load = useCallback(async () => {
    const [domainRows, agents] = await Promise.all([
      listKnowledgeDomains(projectId, token),
      listAgents(projectId, token),
    ])
    setDomains(domainRows)
    setAgentsBySlug(new Map(agents.map((a) => [a.slug, a])))
  }, [projectId, token])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    load()
      .catch(() => {
        if (!cancelled) setDomains(null)
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [load])

  async function refetchQuietly() {
    try {
      await load()
    } catch {
      // Keep showing the last-known rows rather than blanking the panel over a refresh hiccup.
    }
  }

  async function handleApprove(slug: string) {
    setBusySlug(slug)
    try {
      await updateKnowledgeDomain(projectId, slug, { state: 'ACTIVE' }, token)
      showToast(`Domain "${slug}" approved`)
      await refetchQuietly()
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to approve domain'), 'error')
    } finally {
      setBusySlug(null)
    }
  }

  async function handleDismiss(slug: string) {
    setBusySlug(slug)
    try {
      await updateKnowledgeDomain(projectId, slug, { state: 'DISMISSED' }, token)
      showToast(`Domain "${slug}" dismissed`)
      await refetchQuietly()
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to dismiss domain'), 'error')
    } finally {
      setBusySlug(null)
    }
  }

  async function handleCreateSpecialist(slug: string) {
    setBusySlug(slug)
    try {
      await createKnowledgeDomainSpecialist(projectId, slug, token)
      showToast(`Specialist agent created for "${slug}"`)
      await refetchQuietly()
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to create specialist'), 'error')
    } finally {
      setBusySlug(null)
    }
  }

  if (loading) return <Skeleton className="h-10 w-full rounded-[10px]" />
  if (!domains) return null

  const active = domains.filter((d) => d.state === 'ACTIVE')
  const suggested = domains.filter((d) => d.state === 'SUGGESTED')
  if (active.length === 0 && suggested.length === 0) return null

  return (
    <div className="rounded-[10px] border border-border bg-surface text-[13px] divide-y divide-border">
      {active.map((domain) => {
        const owningAgent = domain.owningAgentSlug ? agentsBySlug.get(domain.owningAgentSlug) : undefined
        const avatarEmoji = owningAgent?.avatarEmoji ?? LIBRARIAN_AVATAR_EMOJI
        const avatarColor = isAvatarColorToken(owningAgent?.avatarColor) ? owningAgent.avatarColor : LIBRARIAN_AVATAR_COLOR
        const agentName = owningAgent?.name ?? 'Librarian'

        return (
          <div key={domain.slug} className="flex items-center gap-3 px-4 py-2.5">
            <Link
              href={`/app/projects/${projectId}/knowledge/page?path=${encodeURIComponent(domain.schemaPagePath)}`}
              className="font-medium text-foreground hover:underline shrink-0"
            >
              {domain.displayName}
            </Link>

            <span className="flex items-center gap-1.5 text-foreground-subtle shrink-0">
              <AgentAvatar emoji={avatarEmoji} color={avatarColor} size="sm" />
              {agentName}
            </span>

            <Link
              href={`/app/projects/${projectId}/knowledge/sources?status=PENDING&domain=${encodeURIComponent(domain.slug)}`}
              className="text-foreground-subtle hover:text-foreground hover:underline"
            >
              {domain.pendingCount} pending
            </Link>

            <span className="text-foreground-subtle">{domain.processedCount} processed</span>

            <div className="ml-auto flex items-center gap-2">
              {isAdmin && !domain.owningAgentSlug && (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busySlug === domain.slug}
                  onClick={() => handleCreateSpecialist(domain.slug)}
                >
                  Create specialist
                </Button>
              )}
            </div>
          </div>
        )
      })}

      {suggested.map((domain) => (
        <div key={domain.slug} className="flex items-start gap-3 px-4 py-2.5">
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <span className="font-medium text-foreground">{domain.displayName}</span>
              <span className={cn('inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium', amber.bg, amber.text)}>
                Suggested
              </span>
            </div>
            {domain.suggestionReason && (
              <p className="text-foreground-subtle mt-0.5">{domain.suggestionReason}</p>
            )}
          </div>

          {isAdmin && (
            <div className="flex items-center gap-2 shrink-0">
              <Button
                variant="success"
                size="sm"
                disabled={busySlug === domain.slug}
                onClick={() => handleApprove(domain.slug)}
              >
                Approve
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={busySlug === domain.slug}
                onClick={() => handleDismiss(domain.slug)}
              >
                Dismiss
              </Button>
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
