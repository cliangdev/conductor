'use client'

import { useParams, usePathname } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader, Breadcrumb, type Crumb } from '@/components/layout/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { DefaultAgentBadge } from '@/components/agents/DefaultAgentBadge'
import { AddressableBadge } from '@/components/agents/AddressableBadge'
import { AgentAvatar } from '@/components/agents/AgentAvatar'
import { AgentProvider, useAgent } from '@/contexts/AgentContext'
import { useCan } from '@/contexts/PermissionsContext'

function AgentBreadcrumb() {
  const { projectId, agentId } = useParams<{ projectId: string; agentId: string }>()
  const { agent } = useAgent()
  const base = `/app/projects/${projectId}/agents`
  const crumbs: Crumb[] = [
    { label: 'Agents', href: base },
    { label: agent?.name ?? 'Agent', href: `${base}/${agentId}/overview` },
  ]
  return <Breadcrumb items={crumbs} className="mb-2" />
}

/** Agent identity (name + state), shared across all tabs. */
function AgentDetailHeader() {
  const { agent } = useAgent()
  if (!agent) {
    return <PageHeader title={<span className="text-muted-foreground">Loading…</span>} />
  }
  return (
    <PageHeader
      title={
        <span className="inline-flex items-center gap-2">
          <AgentAvatar emoji={agent.avatarEmoji} color={agent.avatarColor} size="md" />
          {agent.name}
        </span>
      }
      status={
        <div className="flex items-center gap-1.5">
          <Badge variant={agent.state === 'ACTIVE' ? 'status-approved' : 'status-draft'}>
            {agent.state === 'ACTIVE' ? 'Active' : 'Draft'}
          </Badge>
          {agent.isDefault && <DefaultAgentBadge />}
          {agent.addressable && <AddressableBadge />}
        </div>
      }
      description={agent.description ?? undefined}
    />
  )
}

function AgentTabs() {
  const { projectId, agentId } = useParams<{ projectId: string; agentId: string }>()
  const pathname = usePathname()
  const canManage = useCan('agent.manage')
  const agentBase = `/app/projects/${projectId}/agents/${agentId}`
  const active = pathname.includes(`${agentBase}/settings`) ? 'settings' : 'overview'

  const items: TabItem[] = [
    { value: 'overview', label: 'Overview', href: `${agentBase}/overview` },
    ...(canManage ? [{ value: 'settings', label: 'Settings', href: `${agentBase}/settings` }] : []),
  ]

  return <Tabs items={items} value={active} className="mb-4" />
}

export default function AgentLayout({ children }: { children: React.ReactNode }) {
  return (
    <AgentProvider>
      <PageContainer>
        <AgentBreadcrumb />
        <AgentDetailHeader />
        <AgentTabs />
        {children}
      </PageContainer>
    </AgentProvider>
  )
}
