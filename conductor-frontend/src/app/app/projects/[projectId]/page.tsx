import { redirect } from 'next/navigation'

export const dynamic = 'force-dynamic'

// The workspace overview now lives on the Issues list as its Plans tab.
export default async function ProjectHomePage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params
  redirect(`/app/projects/${projectId}/engineering/issues?view=plans`)
}
