'use client'

import { useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { useParams } from 'next/navigation'
import { CheckIcon, SparklesIcon, type LucideIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { useToast } from '@/components/ui/toast'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { KnowledgePageSkeleton } from '@/components/knowledge/KnowledgePageSkeleton'
import { ClaudeConnectionHint } from '@/components/knowledge/ClaudeConnectionHint'
import { KnowledgeBootstrapDialog } from '@/components/knowledge/KnowledgeBootstrapDialog'
import { KnowledgeTypeIcon } from '@/components/knowledge/KnowledgeTypeIcon'
import { Alert } from '@/components/ui/alert'
import { AgentAvatar, isAvatarColorToken, type AvatarColorToken } from '@/components/agents/AgentAvatar'
import {
  getKnowledgeIndex,
  getKnowledgePages,
  enableKnowledge,
  listKnowledgeDomains,
  KNOWLEDGE_LIBRARIAN_SLUG,
  LIBRARIAN_FALLBACK_AVATAR,
  type KnowledgeDomainDto,
} from '@/lib/knowledge-api'
import type { KnowledgePageView } from '@/lib/knowledge-api'
import { apiErrorMessage, listAgents, type Agent } from '@/lib/api'
import { parseKnowledgeIndexPages, filterContentPages, type KnowledgeIndexPage } from '@/lib/knowledgeTree'
import { parseKnowledgeLog, type KnowledgeLogEntry } from '@/lib/knowledgeLog'
import { timeAgo } from '@/lib/format'
import { cn } from '@/lib/utils'

const RECENTLY_UPDATED_LIMIT = 5

// Static lookup, not an interpolated class string — Tailwind can't see a `` `ring-avatar-${color}` ``
// template at build time and would drop the class. Mirrors AgentAvatar's own AVATAR_COLOR_CLASSES.
const RING_COLOR_CLASSES: Record<AvatarColorToken, string> = {
  gray: 'ring-avatar-gray',
  blue: 'ring-avatar-blue',
  amber: 'ring-avatar-amber',
  violet: 'ring-avatar-violet',
  teal: 'ring-avatar-teal',
  green: 'ring-avatar-green',
  rose: 'ring-avatar-rose',
  slate: 'ring-avatar-slate',
}

interface OnboardingTile {
  icon: LucideIcon
  text: string
}

// The enabled-empty first-run state's "what's already filing this wiki" tiles.
const ONBOARDING_TILES: OnboardingTile[] = [
  { icon: CheckIcon, text: 'Work item updates — filed automatically when items change status. Already on.' },
  { icon: CheckIcon, text: 'Merged pull requests — filed automatically via the GitHub connection. Already on.' },
  { icon: SparklesIcon, text: 'Your codebase — seed architecture and feature pages from an existing repo, today.' },
]

export const dynamic = 'force-dynamic'

/** The generated index renders as "# Index" with no bullet links when the bundle has no pages. */
function isEmptyIndex(content: string | undefined | null): boolean {
  if (!content) return true
  return !content.includes('* [')
}

function dirOf(path: string): string {
  const idx = path.lastIndexOf('/')
  return idx < 0 ? '' : path.slice(0, idx)
}

interface RecentEntry {
  entry: KnowledgeLogEntry
  page: KnowledgeIndexPage
}

function RecentlyUpdatedSection({
  projectId,
  entries,
}: {
  projectId: string
  entries: RecentEntry[]
}) {
  if (entries.length === 0) return null
  return (
    <div className="space-y-2">
      <h2 className="text-[11.5px] font-semibold uppercase tracking-wider text-muted-foreground">
        Recently updated
      </h2>
      <Card>
        <CardContent>
          {entries.map(({ entry, page }) => (
            <Link
              key={entry.path}
              href={`/app/projects/${projectId}/knowledge/page?path=${encodeURIComponent(entry.path)}`}
              className="flex items-center gap-3 px-4 py-2.5 text-[13px] hover:bg-surface-2 transition-colors"
            >
              <KnowledgeTypeIcon type={page.type} className="h-3.5 w-3.5 shrink-0 opacity-70" />
              <span className="min-w-0 flex-1">
                <span className="text-foreground font-medium truncate block">{page.title}</span>
                {dirOf(entry.path) && (
                  <span className="text-foreground-subtle text-xs truncate block">{dirOf(entry.path)}</span>
                )}
              </span>
              <span className="shrink-0 inline-flex items-center rounded-full bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground">
                {entry.action === 'CREATE' ? 'new' : 'updated'}
              </span>
              <span className="shrink-0 text-foreground-subtle text-xs text-right">
                {timeAgo(entry.day)}
                {entry.sourceRefs?.[0] && <span className="block truncate max-w-[8rem]">from {entry.sourceRefs[0]}</span>}
              </span>
            </Link>
          ))}
        </CardContent>
      </Card>
    </div>
  )
}

interface AreaCard {
  domain: KnowledgeDomainDto
  pages: KnowledgeIndexPage[]
  updatedDay: string | null
}

function BrowseByAreaSection({ projectId, areas }: { projectId: string; areas: AreaCard[] }) {
  const withPages = areas.filter((a) => a.pages.length > 0).sort((a, b) => a.domain.displayName.localeCompare(b.domain.displayName))
  const empty = areas.filter((a) => a.pages.length === 0)

  // No pageful areas at all — either there are no ACTIVE areas, or every one of them is still
  // empty. Either way a section that's *only* the muted "no pages yet" card isn't worth showing
  // next to a wiki that already has real content elsewhere, so skip it gracefully.
  if (withPages.length === 0) return null

  return (
    <div className="space-y-2">
      <h2 className="text-[11.5px] font-semibold uppercase tracking-wider text-muted-foreground">Browse by area</h2>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        {withPages.map(({ domain, pages, updatedDay }) => (
          <Link
            key={domain.slug}
            href={`/app/projects/${projectId}/knowledge/page?path=${encodeURIComponent(pages[0].path)}`}
            className="block rounded-[10px] border border-border bg-surface p-4 hover:border-border-strong transition-colors"
          >
            <div className="font-medium text-foreground">{domain.displayName}</div>
            <p className="text-sm text-muted-foreground mt-1">
              {pages.length} page{pages.length === 1 ? '' : 's'}
              {updatedDay && ` · updated ${timeAgo(updatedDay)}`}
            </p>
            <p className="text-sm text-foreground-subtle mt-2">
              {domain.description?.trim() || `Pages the Librarian files under ${domain.pathPrefix}`}
            </p>
          </Link>
        ))}

        {empty.length > 0 && (
          <div className="rounded-[10px] border border-border bg-surface-2 p-4">
            <div className="font-medium text-foreground-subtle">
              {empty.map((a) => a.domain.displayName).join(' & ')}
            </div>
            <p className="text-sm text-foreground-subtle mt-1">
              No pages yet — they&apos;ll appear here as the Librarian files matching sources.
            </p>
          </div>
        )}
      </div>
    </div>
  )
}

export default function KnowledgeIndexPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const { can } = usePermissions()
  const { showToast } = useToast()

  const [page, setPage] = useState<KnowledgePageView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [enabling, setEnabling] = useState(false)
  const [librarianAgent, setLibrarianAgent] = useState<Agent | null>(null)
  // Whether the librarian-agent lookup below has resolved — while false, the empty state defaults to
  // the "not enabled" composition (see knowledgeEnabled below) rather than flashing the onboarding one.
  const [agentsChecked, setAgentsChecked] = useState(false)
  const [bootstrapDialogOpen, setBootstrapDialogOpen] = useState(false)
  const [logEntries, setLogEntries] = useState<KnowledgeLogEntry[] | null>(null)
  const [domains, setDomains] = useState<KnowledgeDomainDto[] | null>(null)

  const isAdmin = can('workspace.manage')
  const empty = isEmptyIndex(page?.content)
  // The index is empty in both the "Knowledge never enabled" and "enabled, wiki just hasn't filled in
  // yet" cases (both render a bare "# Index"), and `GET /settings` (the only place knowledgeEnabled
  // lives) is ADMIN-only, so it can't tell every viewer apart. The librarian agent, in contrast, only
  // exists once enabling has provisioned it (KnowledgeWorkflowProvisioner) and listAgents is a plain
  // membership-gated read — so its presence is an honest, role-agnostic proxy for "enabled" that works
  // for admins and non-admins alike.
  const knowledgeEnabled = agentsChecked && librarianAgent !== null

  useEffect(() => {
    if (!accessToken) return
    getKnowledgeIndex(projectId, accessToken)
      .then(setPage)
      .catch((err) => setError(apiErrorMessage(err, 'Failed to load the knowledge index')))
      .finally(() => setLoading(false))
  }, [accessToken, projectId])

  // The librarian agent only matters for the empty-state composition (both its avatar and, via
  // knowledgeEnabled above, which composition to show) — fetched lazily once we know that's what's
  // rendering, for every role (not just admins — non-admins need knowledgeEnabled too).
  useEffect(() => {
    if (!accessToken || loading || error || !empty) return
    let cancelled = false
    listAgents(projectId, accessToken)
      .then((agents) => {
        if (cancelled) return
        setLibrarianAgent(agents.find((a) => a.slug === KNOWLEDGE_LIBRARIAN_SLUG) ?? null)
        setAgentsChecked(true)
      })
      .catch(() => {
        if (!cancelled) {
          setLibrarianAgent(null)
          setAgentsChecked(true)
        }
      })
    return () => {
      cancelled = true
    }
  }, [accessToken, projectId, loading, error, empty])

  // "Recently updated" and "Browse by area" are auxiliary reading-layer sections — each is its own
  // best-effort fetch; a failure just omits that section rather than blocking or erroring the page.
  useEffect(() => {
    if (!accessToken || loading || error || empty) return
    let cancelled = false
    getKnowledgePages(projectId, ['log.md'], accessToken)
      .then((pages) => {
        if (cancelled) return
        setLogEntries(parseKnowledgeLog(pages[0]?.content ?? ''))
      })
      .catch(() => {
        if (!cancelled) setLogEntries(null)
      })
    return () => {
      cancelled = true
    }
  }, [accessToken, projectId, loading, error, empty])

  useEffect(() => {
    if (!accessToken || loading || error || empty) return
    let cancelled = false
    listKnowledgeDomains(projectId, accessToken)
      .then((rows) => {
        if (!cancelled) setDomains(rows)
      })
      .catch(() => {
        if (!cancelled) setDomains(null)
      })
    return () => {
      cancelled = true
    }
  }, [accessToken, projectId, loading, error, empty])

  const contentPages = useMemo(
    () => filterContentPages(parseKnowledgeIndexPages(page?.content ?? '')),
    [page?.content],
  )
  const pagesByPath = useMemo(() => new Map(contentPages.map((p) => [p.path, p])), [contentPages])

  const recentEntries: RecentEntry[] = useMemo(() => {
    if (!logEntries) return []
    const results: RecentEntry[] = []
    // The log lists one line per revision, so a page edited on several days appears several
    // times — keep only its newest entry (the log is already newest-first).
    const seen = new Set<string>()
    for (const entry of logEntries) {
      if (entry.action === 'DELETE' || seen.has(entry.path)) continue
      const found = pagesByPath.get(entry.path)
      if (!found) continue
      seen.add(entry.path)
      results.push({ entry, page: found })
      if (results.length === RECENTLY_UPDATED_LIMIT) break
    }
    return results
  }, [logEntries, pagesByPath])

  const areaCards: AreaCard[] = useMemo(() => {
    if (!domains) return []
    return domains
      .filter((d) => d.state === 'ACTIVE')
      .map((domain) => {
        const pages = contentPages.filter((p) => p.path.startsWith(domain.pathPrefix))
        // Only revisions of live content pages count — otherwise a filing-rules (_schema.md)
        // edit or a deletion would bump the area's "updated" stamp.
        const newestEntry = logEntries?.find(
          (e) => e.path.startsWith(domain.pathPrefix) && pagesByPath.has(e.path),
        )
        return { domain, pages, updatedDay: newestEntry?.day ?? null }
      })
  }, [domains, contentPages, logEntries, pagesByPath])

  async function handleEnable() {
    if (!accessToken) return
    setEnabling(true)
    try {
      await enableKnowledge(projectId, accessToken)
      showToast('Knowledge enabled for this workspace')
      setPage(await getKnowledgeIndex(projectId, accessToken))
      // Re-check for the newly-provisioned librarian agent immediately so the composition below
      // flips from "not enabled" to the onboarding state without waiting for a page refresh.
      try {
        const agents = await listAgents(projectId, accessToken)
        setLibrarianAgent(agents.find((a) => a.slug === KNOWLEDGE_LIBRARIAN_SLUG) ?? null)
        setAgentsChecked(true)
      } catch {
        // best-effort — the lazy effect above covers this on the next mount
      }
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to enable Knowledge'), 'error')
    } finally {
      setEnabling(false)
    }
  }

  if (loading) {
    return <KnowledgePageSkeleton fullHeight />
  }

  if (error) {
    return (
      <div className="max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-6">
        <Alert variant="destructive">{error}</Alert>
      </div>
    )
  }

  if (empty) {
    const avatarEmoji = librarianAgent?.avatarEmoji ?? LIBRARIAN_FALLBACK_AVATAR.emoji
    const avatarColor = isAvatarColorToken(librarianAgent?.avatarColor)
      ? librarianAgent.avatarColor
      : LIBRARIAN_FALLBACK_AVATAR.color
    const avatar = (
      <div className={cn('rounded-full p-1 ring-2', RING_COLOR_CLASSES[avatarColor])}>
        <AgentAvatar emoji={avatarEmoji} color={avatarColor} size="lg" />
      </div>
    )

    if (knowledgeEnabled) {
      return (
        <div className="flex flex-col items-center justify-center text-center px-4 py-12 h-full">
          {avatar}
          <h2 className="text-lg font-semibold text-foreground mt-6 mb-2">The librarian is on duty</h2>
          <p className="text-sm text-muted-foreground max-w-sm">
            Nobody writes this wiki by hand. As your team works, the librarian reads what happened and
            files it into pages — which get more accurate over time.
          </p>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 w-full max-w-2xl mt-6">
            {ONBOARDING_TILES.map((tile) => (
              <Card key={tile.text} className="p-4 text-left">
                <tile.icon className="h-4 w-4 text-muted-foreground mb-2" />
                <p className="text-sm text-foreground">{tile.text}</p>
              </Card>
            ))}
          </div>

          {isAdmin ? (
            <>
              <Button size="sm" className="mt-6" onClick={() => setBootstrapDialogOpen(true)}>
                Bootstrap from a repo
              </Button>
              <p className="text-xs text-muted-foreground mt-3">
                Or wait — pages appear on their own as work flows in.
              </p>
              {accessToken && (
                <div className="mt-4 w-full max-w-sm">
                  <ClaudeConnectionHint projectId={projectId} token={accessToken} />
                </div>
              )}
              {bootstrapDialogOpen && accessToken && (
                <KnowledgeBootstrapDialog
                  projectId={projectId}
                  token={accessToken}
                  onClose={() => setBootstrapDialogOpen(false)}
                />
              )}
            </>
          ) : (
            <p className="text-xs text-muted-foreground mt-6">Pages appear on their own as work flows in.</p>
          )}
        </div>
      )
    }

    return (
      // Mirrors EmptyState's rhythm (icon-tile → title → description → action) — the librarian's
      // AgentAvatar stands in for the icon tile, so this is composed by hand rather than through
      // the EmptyState primitive (its icon slot assumes a lucide glyph in a bg-muted tile, not an
      // already-tinted circular avatar).
      <div className="flex flex-col items-center justify-center text-center px-4 py-12 h-full">
        {avatar}
        <h2 className="text-lg font-semibold text-foreground mt-6 mb-2">The knowledge base is empty</h2>
        {isAdmin ? (
          <>
            <p className="text-sm text-muted-foreground max-w-sm mb-6">
              Enabling Knowledge provisions the librarian and bootstrap workflows that keep this
              workspace&apos;s wiki up to date.
            </p>
            <Button size="sm" onClick={handleEnable} disabled={enabling}>
              {enabling ? 'Enabling…' : 'Enable Knowledge'}
            </Button>
            {accessToken && (
              <div className="mt-4 w-full max-w-sm">
                <ClaudeConnectionHint projectId={projectId} token={accessToken} />
              </div>
            )}
          </>
        ) : (
          <p className="text-sm text-muted-foreground max-w-sm">
            Ask a workspace admin to enable Knowledge for this workspace.
          </p>
        )}
      </div>
    )
  }

  const subtitleParts = [
    `${contentPages.length} page${contentPages.length === 1 ? '' : 's'}`,
    'maintained by the Librarian',
  ]
  if (logEntries && logEntries.length > 0) {
    subtitleParts.push(`last updated ${timeAgo(logEntries[0].day)}`)
  }

  return (
    <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
      <div className="max-w-[45rem] mx-auto space-y-6">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Knowledge</h1>
          <p className="text-sm text-muted-foreground mt-1">{subtitleParts.join(' · ')}</p>
        </div>

        <RecentlyUpdatedSection projectId={projectId} entries={recentEntries} />
        <BrowseByAreaSection projectId={projectId} areas={areaCards} />
      </div>
    </div>
  )
}
