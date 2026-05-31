'use client'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import { getDoc } from '@/lib/docs-api'
import type { ProjectDoc } from '@/lib/docs-api'
import { DocViewer } from '@/components/docs/DocViewer'
import { DocHistoryPanel } from '@/components/docs/DocHistoryPanel'
import { Button, buttonVariants } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { ChevronLeft, History, Pencil } from 'lucide-react'

export const dynamic = 'force-dynamic'

export default function DocDetailPage() {
  const { projectId, docId } = useParams<{ projectId: string; docId: string }>()
  const { accessToken, user } = useAuth()

  const [doc, setDoc] = useState<ProjectDoc | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [showHistory, setShowHistory] = useState(false)

  useEffect(() => {
    if (!accessToken) return

    getDoc(projectId, docId, accessToken)
      .then(setDoc)
      .catch((err) => setError(err instanceof Error ? err.message : 'Failed to load doc'))
      .finally(() => setLoading(false))
  }, [accessToken, projectId, docId])

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
      <div className="border-b border-border bg-background px-4 sm:px-6 py-4 shrink-0">
        <div className="flex items-center gap-3">
          <Link
            href={`/app/projects/${projectId}/docs`}
            className="shrink-0 text-muted-foreground hover:text-foreground transition-colors"
            title="Back to docs"
          >
            <ChevronLeft className="h-5 w-5" />
          </Link>
          <h1 className="text-lg sm:text-xl font-semibold text-foreground flex-1 min-w-0 truncate">
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
        <div className="h-full overflow-y-auto p-4 md:p-6">
          {doc.content ? (
            <DocViewer
              doc={doc}
              projectId={projectId}
              token={accessToken!}
              currentUserId={user?.id ?? ''}
            />
          ) : (
            <p className="text-muted-foreground text-sm">No content yet.</p>
          )}
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
