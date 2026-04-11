import { redirect } from 'next/navigation'

export const dynamic = 'force-dynamic'

export default async function SettingsPage({ params }: { params: Promise<{ projectId: string }> }) {
  const { projectId } = await params
  redirect(`/app/projects/${projectId}/settings/notifications`)
}
