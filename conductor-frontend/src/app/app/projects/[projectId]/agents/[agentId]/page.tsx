import { redirect } from 'next/navigation'

// The agent detail's canonical landing tab is Overview. Keep the bare `[agentId]`
// route as a redirect so the tabs share one URL shape (/overview, /settings).
export default async function AgentIndexPage({
  params,
}: {
  params: Promise<{ projectId: string; agentId: string }>
}) {
  const { projectId, agentId } = await params
  redirect(`/app/projects/${projectId}/agents/${agentId}/overview`)
}
