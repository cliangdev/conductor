'use client'

import { useCallback, useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import { getDoc, setDocTaskState } from '@/lib/docs-api'
import type { ProjectDoc } from '@/lib/docs-api'
import { apiErrorMessage, apiGet } from '@/lib/api'
import { DocViewer } from '@/components/docs/DocViewer'
import { DocHistoryPanel } from '@/components/docs/DocHistoryPanel'
import { Button, buttonVariants } from '@/components/ui/button'
import { toastError } from '@/components/ui/toast'
import { toggleTaskLine } from '@/lib/task-list'
import { cn } from '@/lib/utils'
import type { MemberRole } from '@/types'
import { ChevronLeft, History, Pencil } from 'lucide-react'

interface Member {
  userId: string
  role: MemberRole
}

export const dynamic = 'force-dynamic'

export default function DocDetailPage() {
  const { projectId, docId } = useParams<{ projectId: string; docId: string }>()
  const { accessToken, user } = useAuth()

  const [doc, setDoc] = useState<ProjectDoc | null>(null)
  const [userRole, setUserRole] = useState<MemberRole>('REVIEWER')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [showHistory, setShowHistory] = useState(false)

  useEffect(() => {
    if (!accessToken) return

    getDoc(projectId, docId, accessToken)
      .then(setDoc)
      .catch((err) => setError(apiErrorMessage(err, 'Failed to load doc')))
      .finally(() => setLoading(false))
  }, [accessToken, projectId, docId])

  // Separate from the doc fetch so a members failure never blocks reading: it only decides whether
  // checkboxes are interactive, and the default (REVIEWER) is the safe, read-only answer.
  useEffect(() => {
    if (!accessToken || !user) return

    apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, accessToken)
      .then((members) => {
        const me = members.find((m) => m.userId === user.id)
        if (me) setUserRole(me.role)
      })
      .catch(() => {})
  }, [accessToken, user, projectId])

  const canEditDoc = userRole === 'ADMIN' || userRole === 'CREATOR'

  const handleToggleTask = useCallback(
    async (lineNumber: number, checked: boolean) => {
      if (!accessToken) return

      // Applied inside the updater so it always reads the latest content: clicking two boxes in quick
      // succession must not have the second click compute from a snapshot taken before the first.
      const flip = (state: boolean) => (current: ProjectDoc | null) => {
        if (!current?.content) return current
        const next = toggleTaskLine(current.content, lineNumber, state)
        return next === null ? current : { ...current, content: next }
      }

      setDoc(flip(checked))

      try {
        await setDocTaskState(projectId, docId, lineNumber, checked, accessToken)
        // Deliberately not adopting the response body: two quick toggles on different lines race, and
        // the first reply predates the second flip, so applying it would undo a box just ticked.
      } catch (err) {
        // Revert by flipping this one line back rather than restoring a whole-content snapshot — the
        // inverse only touches the failed line, leaving any toggle that landed meanwhile intact.
        setDoc(flip(!checked))
        toastError(apiErrorMessage(err, 'Failed to update the checklist item'))
      }
    },
    [accessToken, projectId, docId]
  )

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64 text-muted-foreground">
        Loading...
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-64 text-destructive">
        Error: {error}
      </div>
    )
  }

  if (!doc) return null

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="border-b border-border bg-background px-4 sm:px-6 lg:px-8 py-4 shrink-0">
        <div className="max-w-6xl mx-auto flex items-center gap-3">
          <Link
            href={`/app/projects/${projectId}/docs`}
            className="shrink-0 text-muted-foreground hover:text-foreground transition-colors"
            title="Back to docs"
          >
            <ChevronLeft className="h-5 w-5" />
          </Link>
          <h1 className="text-xl sm:text-2xl font-semibold text-foreground flex-1 min-w-0 truncate">
            {doc.title}
          </h1>
          <div className="flex items-center gap-2 shrink-0">
            <Button variant="outline" size="sm" onClick={() => setShowHistory(true)}>
              <History className="h-3.5 w-3.5 mr-1.5" />
              History
            </Button>
            <Link
              href={`/app/projects/${projectId}/docs/${docId}/edit`}
              className={cn(buttonVariants({ variant: 'outline', size: 'sm' }))}
            >
              <Pencil className="h-3.5 w-3.5 mr-1.5" />
              Edit
            </Link>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 relative overflow-hidden">
        <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">
          <div className="max-w-6xl mx-auto">
            {doc.content ? (
              <DocViewer
                doc={doc}
                projectId={projectId}
                token={accessToken!}
                currentUserId={user?.id ?? ''}
                onToggleTask={handleToggleTask}
                tasksReadOnly={!canEditDoc}
              />
            ) : (
              <p className="text-muted-foreground text-sm">No content yet.</p>
            )}
          </div>
        </div>

        {showHistory && (
          <DocHistoryPanel
            projectId={projectId}
            docId={docId}
            token={accessToken!}
            onClose={() => setShowHistory(false)}
            onRestored={(updated) => {
              setDoc(updated)
              setShowHistory(false)
            }}
          />
        )}
      </div>
    </div>
  )
}
