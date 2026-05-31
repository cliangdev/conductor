'use client'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { useEditorChrome } from '@/contexts/EditorChromeContext'
import { DocTree } from '@/components/docs/DocTree'
import { DocSearch } from '@/components/docs/DocSearch'
import { apiGet } from '@/lib/api'
import type { MemberRole } from '@/types'
import { ChevronRightIcon, ChevronLeftIcon } from 'lucide-react'

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
  const { fullscreen, docsTreeCollapsed, setDocsTreeCollapsed } = useEditorChrome()
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

  const railHidden = fullscreen || docsTreeCollapsed

  return (
    <div className="flex h-full">
      {/* Left panel: DocSearch + DocTree */}
      {!railHidden && (
        <div className="flex shrink-0 border-r border-border">
          {/* Scrollable content */}
          <div className="w-56 bg-sidebar-bg overflow-y-auto">
            {accessToken && (
              <>
                <DocSearch
                  projectId={projectId}
                  token={accessToken}
                  onResultSelect={(docId) => router.push(`/app/projects/${projectId}/docs/${docId}`)}
                />
                <DocTree
                  projectId={projectId}
                  token={accessToken}
                  userRole={userRole}
                  onDocSelect={handleDocSelect}
                />
              </>
            )}
          </div>
          {/* Collapse strip on the right edge of the rail */}
          <button
            type="button"
            onClick={() => setDocsTreeCollapsed(true)}
            title="Collapse docs panel"
            className="w-4 shrink-0 bg-sidebar-bg flex items-center justify-center text-muted-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
          >
            <ChevronLeftIcon className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {/* Expand strip (when collapsed but not fullscreen) */}
      {docsTreeCollapsed && !fullscreen && (
        <button
          type="button"
          onClick={() => setDocsTreeCollapsed(false)}
          title="Expand docs panel"
          className="w-5 shrink-0 border-r border-border bg-sidebar-bg flex items-center justify-center hover:bg-accent transition-colors text-muted-foreground"
        >
          <ChevronRightIcon className="h-3.5 w-3.5" />
        </button>
      )}

      {/* Right panel: page content */}
      <div className="flex-1 overflow-hidden">{children}</div>
    </div>
  )
}
