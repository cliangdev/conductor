'use client'

import { useParams, usePathname } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { Breadcrumb, type Crumb } from '@/components/layout/PageHeader'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { AgentProvider, useAgent } from '@/contexts/AgentContext'
import { useCan } from '@/contexts/PermissionsContext'

function AgentBreadcrumb() {
  const { projectId, agentId } = useParams<{ projectId: string; agentId: string }>()
  const { agent } = useAgent()
  const base = `/app/projects/${projectId}/agents`
  const crumbs: Crumb[] = [
    { label: 'Agents', href: base },
    { label: agent?.name ?? 'Agent', href: `${base}/${agentId}` },
  ]
  return <Breadcrumb items={crumbs} className="mb-2" />
}

function AgentTabs() {
  const { projectId, agentId } = useParams<{ projectId: string; agentId: string }>()
  const pathname = usePathname()
  const canManage = useCan('agent.manage')
  const agentBase = `/app/projects/${projectId}/agents/${agentId}`
  const active = pathname.includes(`${agentBase}/settings`) ? 'settings' : 'overview'

  const items: TabItem[] = [
    { value: 'overview', label: 'Overview', href: agentBase },
    ...(canManage ? [{ value: 'settings', label: 'Settings', href: `${agentBase}/settings` }] : []),
  ]

  return <Tabs items={items} value={active} className="mb-4" />
}

export default function AgentLayout({ children }: { children: React.ReactNode }) {
  return (
    <AgentProvider>
      <PageContainer>
        <AgentBreadcrumb />
        <AgentTabs />
        {children}
      </PageContainer>
    </AgentProvider>
  )
}
