import { redirect } from 'next/navigation'

// COND-22: the Issues list migrated to the generic Work Item page. /issues is kept as a stable alias
// that redirects to the ENGINEERING Workflow's view. Server-side redirect — runs before render, no
// flicker. The issue detail route (/issues/[issueId]) is unchanged.
export default async function IssuesPage({
  params,
}: {
  params: Promise<{ projectId: string }>
}) {
  const { projectId } = await params
  redirect(`/app/projects/${projectId}/work/ENGINEERING`)
}
