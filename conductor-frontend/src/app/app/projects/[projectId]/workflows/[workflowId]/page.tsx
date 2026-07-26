import { redirect } from 'next/navigation';

// The workflow detail's canonical landing tab is Runs (run history), not a summary — matching
// GitHub Actions/Dagster/n8n/Prefect convention. Keep the bare `[workflowId]` route as a redirect
// so the two tabs (/runs, /definition) share one URL shape and old links still resolve.
export default async function WorkflowIndexPage({
  params,
}: {
  params: Promise<{ projectId: string; workflowId: string }>;
}) {
  const { projectId, workflowId } = await params;
  redirect(`/app/projects/${projectId}/workflows/${workflowId}/runs`);
}
