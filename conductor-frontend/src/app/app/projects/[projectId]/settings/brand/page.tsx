'use client'

export const dynamic = 'force-dynamic'

import { useParams } from 'next/navigation'
import { BrandKitForm } from '@/components/marketing/brand/BrandKitForm'
import { PageHeader } from '@/components/layout/PageHeader'
import { useAuth } from '@/contexts/AuthContext'
import { settingsBreadcrumbs } from '@/lib/navigation'

export default function BrandSettingsPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()

  return (
    <div>
      <PageHeader
        title="Brand"
        breadcrumbs={settingsBreadcrumbs(projectId, 'settings-brand')}
        description="Colours, fonts, logos and copy rules a Creative renders with."
      />
      {accessToken && <BrandKitForm projectId={projectId} token={accessToken} />}
    </div>
  )
}
