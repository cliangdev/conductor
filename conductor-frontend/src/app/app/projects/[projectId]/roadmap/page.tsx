import { redirect } from 'next/navigation'

export const dynamic = 'force-dynamic'

// The roadmap now lives on the Issues list as its Roadmap tab.
export default async function RoadmapRedirectPage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params
  redirect(`/app/projects/${projectId}/engineering/issues?view=roadmap`)
}
