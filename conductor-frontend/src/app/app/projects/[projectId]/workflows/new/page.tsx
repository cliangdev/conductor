import { redirect } from 'next/navigation';

export const dynamic = 'force-dynamic';

// Workflow authoring moved to Settings → Workflows. Keep this route as a
// redirect so existing links/bookmarks don't break.
export default async function NewWorkflowRedirect({
  params,
}: {
  params: Promise<{ projectId: string }>;
}) {
  const { projectId } = await params;
  redirect(`/app/projects/${projectId}/settings/workflows/new`);
}
