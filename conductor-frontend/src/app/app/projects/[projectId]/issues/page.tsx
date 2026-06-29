import { redirect } from 'next/navigation'

// COND-22: the Issues list migrated to the generic Work Item page. /issues is kept as a stable alias
// that redirects to the default (ENGINEERING) Workflow's workflow-scoped view. Server-side redirect —
// runs before render, no flicker. The area/noun segments match the default Workflow (Engineering →
// Issues), hardcoded to mirror the DEFAULT_WORKFLOW_SLUG default. The detail route (/issues/[issueId])
// is unchanged.
export default async function IssuesPage({
  params,
}: {
  params: Promise<{ projectId: string }>
}) {
  const { projectId } = await params
  redirect(`/app/projects/${projectId}/engineering/issues`)
}
