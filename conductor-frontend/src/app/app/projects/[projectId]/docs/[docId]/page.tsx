'use client'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { getDoc } from '@/lib/docs-api'
import type { ProjectDoc } from '@/lib/docs-api'

export const dynamic = 'force-dynamic'

export default function DocDetailPage() {
  const { projectId, docId } = useParams<{ projectId: string; docId: string }>()
  const { accessToken } = useAuth()

  const [doc, setDoc] = useState<ProjectDoc | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

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
    <div className="p-4 sm:p-6 max-w-3xl">
      <h1 className="text-2xl font-semibold text-foreground mb-4">{doc.title}</h1>
      {doc.content ? (
        <pre className="text-sm text-foreground whitespace-pre-wrap font-sans">{doc.content}</pre>
      ) : (
        <p className="text-muted-foreground text-sm">No content yet.</p>
      )}
    </div>
  )
}
