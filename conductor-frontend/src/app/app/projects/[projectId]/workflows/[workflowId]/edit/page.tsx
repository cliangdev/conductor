import { redirect } from 'next/navigation';

export const dynamic = 'force-dynamic';

// Workflow authoring moved to Settings → Workflows. Keep this route as a
// redirect so existing links/bookmarks don't break.
export default async function EditWorkflowRedirect({
  params,
}: {
  params: Promise<{ projectId: string; workflowId: string }>;
}) {
  const { projectId, workflowId } = await params;
  redirect(`/app/projects/${projectId}/settings/workflows/${workflowId}/edit`);
}
