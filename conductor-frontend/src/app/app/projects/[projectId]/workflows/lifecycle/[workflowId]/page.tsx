import { redirect } from 'next/navigation'

// The lifecycle detail's canonical landing tab is Overview. Keep the bare `[workflowId]` route as a
// redirect so the tabs share one URL shape (/overview, /versions, /editor) and old links still resolve.
export default async function LifecycleIndexPage({
  params,
}: {
  params: Promise<{ projectId: string; workflowId: string }>
}) {
  const { projectId, workflowId } = await params
  redirect(`/app/projects/${projectId}/workflows/lifecycle/${workflowId}/overview`)
}
