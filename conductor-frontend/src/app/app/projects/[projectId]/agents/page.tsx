'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import Link from 'next/link'
import { ArrowRightIcon, BotIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { listAgents, type Agent } from '@/lib/api'
import { DefaultAgentBadge } from '@/components/agents/DefaultAgentBadge'
import { AddressableBadge } from '@/components/agents/AddressableBadge'
import { AgentAvatar } from '@/components/agents/AgentAvatar'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { TagBadge } from '@/components/ui/TagBadge'
import { Can } from '@/components/auth/Can'
import { Card } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'

export default function AgentsPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const [agents, setAgents] = useState<Agent[]>([])
  const [loading, setLoading] = useState(true)

  const base = `/app/projects/${projectId}/agents`

  useEffect(() => {
    if (!accessToken || !projectId) return
    listAgents(projectId, accessToken)
      .then(setAgents)
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [projectId, accessToken])

  const newAgentAction = (
    <Can do="agent.manage">
      <Button onClick={() => router.push(`${base}/new`)}>New agent</Button>
    </Can>
  )

  return (
    <PageContainer>
      <PageHeader
        title="Agents"
        description="Named AI agents that can analyze data and run tools inside your workflows."
        actions={newAgentAction}
      />

      <Link
        href={`/app/projects/${projectId}/settings/providers`}
        className="mb-4 flex items-center gap-1 text-sm text-primary hover:underline w-fit"
      >
        Manage AI providers
        <ArrowRightIcon className="h-3.5 w-3.5" />
      </Link>

      {loading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {[0, 1, 2].map((i) => <Skeleton key={i} className="h-32" />)}
        </div>
      ) : agents.length === 0 ? (
        <Card>
          <EmptyState
            icon={BotIcon}
            title="No agents yet"
            description="Create an agent to analyze data and automate work in your workflows."
            action={
              <Can do="agent.manage">
                <Button size="sm" onClick={() => router.push(`${base}/new`)}>New agent</Button>
              </Can>
            }
          />
        </Card>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {agents.map((agent) => (
            <div
              key={agent.id}
              onClick={() => router.push(`${base}/${agent.id}/overview`)}
              className="bg-card rounded-lg border border-border p-5 h-full flex flex-col gap-2 hover:border-primary/50 transition-colors cursor-pointer"
            >
              <div className="flex items-center gap-2 min-w-0">
                <AgentAvatar emoji={agent.avatarEmoji} color={agent.avatarColor} size="sm" />
                <span className="font-medium text-foreground truncate">{agent.name}</span>
                {agent.isDefault && <DefaultAgentBadge />}
                {agent.addressable && <AddressableBadge />}
                {agent.tag && <TagBadge tag={agent.tag} />}
              </div>
              {agent.description && (
                <p className="text-sm text-muted-foreground line-clamp-2">{agent.description}</p>
              )}
              <div className="mt-auto pt-2 flex items-center gap-3 text-xs text-muted-foreground">
                <span>{agent.provider}{agent.model ? ` · ${agent.model}` : ''}</span>
                <span>·</span>
                <span>{agent.toolIds.length} tool{agent.toolIds.length === 1 ? '' : 's'}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </PageContainer>
  )
}
