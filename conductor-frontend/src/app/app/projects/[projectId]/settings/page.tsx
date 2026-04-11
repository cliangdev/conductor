import { redirect } from 'next/navigation'

export const dynamic = 'force-dynamic'

export default function SettingsPage({ params }: { params: { projectId: string } }) {
  redirect(`/app/projects/${params.projectId}/settings/notifications`)
}
