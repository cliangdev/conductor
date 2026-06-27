'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import Link from 'next/link'
import { BotIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { listAgents, type Agent } from '@/lib/api'
import { useCanMutate } from '@/components/agents/useCanMutate'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'

function AgentCard({ agent }: { agent: Agent }) {
  return (
    <div className="bg-card rounded-lg border border-border p-5 h-full flex flex-col gap-2 hover:border-primary/50 transition-colors">
      <div className="flex items-start justify-between gap-2">
        <div className="flex items-center gap-2 min-w-0">
          <BotIcon className="h-4 w-4 shrink-0 text-muted-foreground" />
          <span className="font-medium text-foreground truncate">{agent.name}</span>
        </div>
        <Badge variant={agent.state === 'ACTIVE' ? 'status-approved' : 'status-draft'}>
          {agent.state === 'ACTIVE' ? 'Active' : 'Draft'}
        </Badge>
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
  )
}

export default function AgentsPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const { canMutate } = useCanMutate(projectId)
  const [agents, setAgents] = useState<Agent[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!accessToken || !projectId) return
    listAgents(projectId, accessToken)
      .then(setAgents)
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [projectId, accessToken])

  const newAgentHref = `/app/projects/${projectId}/settings/agents/new`

  return (
    <PageContainer>
      <PageHeader
        title="Agents"
        description="Named AI agents that can analyze data and run tools inside your workflows."
        actions={canMutate ? (
          <Button onClick={() => router.push(newAgentHref)}>New Agent</Button>
        ) : undefined}
      />

      {loading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {[0, 1, 2].map((i) => <div key={i} className="h-32 bg-muted rounded-lg animate-pulse" />)}
        </div>
      ) : agents.length === 0 ? (
        <div className="bg-card rounded-lg border border-border p-12 text-center">
          <BotIcon className="h-8 w-8 mx-auto text-muted-foreground" />
          <h2 className="mt-3 text-lg font-medium text-foreground">No agents yet</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Create an agent to analyze data and automate work in your workflows.
          </p>
          {canMutate && (
            <Link
              href={newAgentHref}
              className="inline-flex items-center mt-4 rounded-md bg-primary text-primary-foreground px-4 py-2 text-sm font-medium hover:bg-primary/90"
            >
              New Agent
            </Link>
          )}
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {agents.map((agent) => (
            canMutate ? (
              <Link key={agent.id} href={`/app/projects/${projectId}/settings/agents/${agent.id}/edit`}>
                <AgentCard agent={agent} />
              </Link>
            ) : (
              <AgentCard key={agent.id} agent={agent} />
            )
          ))}
        </div>
      )}
    </PageContainer>
  )
}
