'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage } from '@/lib/api'
import { getDoc } from '@/lib/docs-api'
import type { ProjectDoc } from '@/lib/docs-api'
import { DocEditor } from '@/components/docs/DocEditor'
import { usePermissions } from '@/contexts/PermissionsContext'

export default function DocEditPage() {
  const { projectId, docId } = useParams<{ projectId: string; docId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()

  const [doc, setDoc] = useState<ProjectDoc | null>(null)
  const { can, loading: permissionsLoading } = usePermissions()
  const canEdit = can('doc.edit')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!accessToken) return

    async function load() {
      try {
        setDoc(await getDoc(projectId, docId, accessToken!))
      } catch (err) {
        setError(apiErrorMessage(err, 'Failed to load document'))
      } finally {
        setLoading(false)
      }
    }

    load()
  }, [accessToken, projectId, docId])

  function handleSaved(updatedDoc: ProjectDoc) {
    router.push(`/app/projects/${projectId}/docs/${updatedDoc.id}`)
  }

  function handleCancel() {
    router.push(`/app/projects/${projectId}/docs/${docId}`)
  }

  // Wait for the role too: the editor closes itself for a reader, so an unresolved role must not look like one.
  if (loading || permissionsLoading) {
    return (
      <div className="flex items-center justify-center h-64 text-muted-foreground">
        Loading…
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
      <DocEditor
        doc={doc}
        projectId={projectId}
        token={accessToken!}
        canEdit={canEdit}
        onSaved={handleSaved}
        onCancel={handleCancel}
      />
    </div>
  )
}
