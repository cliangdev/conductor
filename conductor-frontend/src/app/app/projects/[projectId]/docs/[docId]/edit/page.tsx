'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, apiErrorMessage } from '@/lib/api'
import { getDoc } from '@/lib/docs-api'
import type { ProjectDoc } from '@/lib/docs-api'
import { DocEditor } from '@/components/docs/DocEditor'
import type { MemberRole } from '@/types'

interface Member {
  userId: string
  role: MemberRole
}

export default function DocEditPage() {
  const { projectId, docId } = useParams<{ projectId: string; docId: string }>()
  const { accessToken, user } = useAuth()
  const router = useRouter()

  const [doc, setDoc] = useState<ProjectDoc | null>(null)
  const [userRole, setUserRole] = useState<MemberRole>('REVIEWER')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!accessToken || !user) return

    async function load() {
      try {
        const [fetchedDoc, members] = await Promise.all([
          getDoc(projectId, docId, accessToken!),
          apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, accessToken!),
        ])
        setDoc(fetchedDoc)
        const me = members.find((m) => m.userId === user!.id)
        if (me) setUserRole(me.role)
      } catch (err) {
        setError(apiErrorMessage(err, 'Failed to load document'))
      } finally {
        setLoading(false)
      }
    }

    load()
  }, [accessToken, user, projectId, docId])

  function handleSaved(updatedDoc: ProjectDoc) {
    router.push(`/app/projects/${projectId}/docs/${updatedDoc.id}`)
  }

  function handleCancel() {
    router.push(`/app/projects/${projectId}/docs/${docId}`)
  }

  if (loading) {
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
        userRole={userRole}
        onSaved={handleSaved}
        onCancel={handleCancel}
      />
    </div>
  )
}
