import { redirect } from 'next/navigation';

// The workflow detail's canonical landing tab is Overview. Keep the bare
// `[workflowId]` route as a redirect so the three tabs share one URL shape
// (/overview, /runs, /settings) and old links still resolve.
export default async function WorkflowIndexPage({
  params,
}: {
  params: Promise<{ projectId: string; workflowId: string }>;
}) {
  const { projectId, workflowId } = await params;
  redirect(`/app/projects/${projectId}/workflows/${workflowId}/overview`);
}
