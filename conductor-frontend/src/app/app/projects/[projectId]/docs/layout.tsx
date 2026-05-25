'use client'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { DocTree } from '@/components/docs/DocTree'
import { apiGet } from '@/lib/api'
import type { MemberRole } from '@/types'

interface Member {
  userId: string
  role: MemberRole
}

export default function DocsLayout({
  children,
}: {
  children: React.ReactNode
}) {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken, user } = useAuth()
  const router = useRouter()
  const [userRole, setUserRole] = useState<MemberRole>('REVIEWER')

  useEffect(() => {
    if (!accessToken || !user) return

    async function fetchRole() {
      try {
        const members = await apiGet<Member[]>(
          `/api/v1/projects/${projectId}/members`,
          accessToken!
        )
        const me = members.find((m) => m.userId === user!.id)
        if (me) setUserRole(me.role)
      } catch {
        // Leave as REVIEWER on error
      }
    }

    fetchRole()
  }, [accessToken, projectId, user])

  function handleDocSelect(docId: string) {
    router.push(`/app/projects/${projectId}/docs/${docId}`)
  }

  return (
    <div className="flex h-full">
      {/* Left panel: DocTree */}
      <div className="w-60 shrink-0 border-r border-border bg-sidebar-bg overflow-y-auto">
        {accessToken && (
          <DocTree
            projectId={projectId}
            token={accessToken}
            userRole={userRole}
            onDocSelect={handleDocSelect}
          />
        )}
      </div>

      {/* Right panel: page content */}
      <div className="flex-1 overflow-y-auto">{children}</div>
    </div>
  )
}
