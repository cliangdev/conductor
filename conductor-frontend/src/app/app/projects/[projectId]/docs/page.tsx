'use client'

import { useEffect, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import { getFolders, getDocs } from '@/lib/docs-api'
import type { DocFolder, ProjectDocSummary } from '@/lib/docs-api'

export const dynamic = 'force-dynamic'

export default function DocsPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()

  const [folders, setFolders] = useState<DocFolder[]>([])
  const [docs, setDocs] = useState<ProjectDocSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!accessToken) return

    async function fetchAll() {
      try {
        const [folderData, docData] = await Promise.all([
          getFolders(projectId, accessToken!),
          getDocs(projectId, null, accessToken!),
        ])
        setFolders(folderData)
        setDocs(docData)
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load docs')
      } finally {
        setLoading(false)
      }
    }

    fetchAll()
  }, [accessToken, projectId])

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64 text-muted-foreground">
        Loading docs...
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

  const isEmpty = folders.length === 0 && docs.length === 0

  if (isEmpty) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-4 text-muted-foreground">
        <p className="text-sm">No docs yet</p>
        <button
          type="button"
          className="px-4 py-2 rounded-md bg-primary text-primary-foreground text-sm font-medium hover:bg-primary/90 transition-colors"
        >
          Create your first doc
        </button>
      </div>
    )
  }

  return (
    <div className="p-4 sm:p-6">
      <h1 className="text-xl font-semibold text-foreground mb-4">Docs</h1>

      {docs.length > 0 && (
        <div className="space-y-1">
          {docs.map((doc) => (
            <Link
              key={doc.id}
              href={`/app/projects/${projectId}/docs/${doc.id}`}
              className="flex items-center gap-3 px-3 py-2 rounded-md hover:bg-muted transition-colors"
            >
              <span className="flex-1 text-sm text-foreground truncate">{doc.title}</span>
              <span className="text-xs text-muted-foreground shrink-0">
                {new Date(doc.updatedAt).toLocaleDateString(undefined, {
                  year: 'numeric',
                  month: 'short',
                  day: 'numeric',
                })}
              </span>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
