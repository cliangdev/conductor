'use client'

export const dynamic = 'force-dynamic'

import { useOrg } from '@/contexts/OrgContext'

export default function OrgSettingsPage() {
  const { activeOrg } = useOrg()

  if (!activeOrg) {
    return (
      <div className="max-w-2xl mx-auto px-4 py-8">
        <p className="text-sm text-muted-foreground">No organization selected.</p>
      </div>
    )
  }

  return (
    <div className="max-w-2xl mx-auto px-4 py-8">
      <div className="mb-6">
        <p className="text-xs text-muted-foreground mb-0.5">{activeOrg.name}</p>
        <h1 className="text-2xl font-bold text-foreground">Settings</h1>
      </div>

      <div className="bg-card rounded-lg border border-border p-6 space-y-4">
        <div>
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-1">Name</p>
          <p className="text-sm text-foreground">{activeOrg.name}</p>
        </div>
        <div>
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-1">Slug</p>
          <p className="text-sm text-foreground font-mono">{activeOrg.slug}</p>
        </div>
        <div>
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-1">Created</p>
          <p className="text-sm text-foreground">
            {new Date(activeOrg.createdAt).toLocaleDateString()}
          </p>
        </div>
      </div>
    </div>
  )
}
