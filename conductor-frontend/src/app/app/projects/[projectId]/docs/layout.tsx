'use client'


import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { useEditorChrome } from '@/contexts/EditorChromeContext'
import { DocTree } from '@/components/docs/DocTree'
import { DocSearch } from '@/components/docs/DocSearch'
import { useCan } from '@/contexts/PermissionsContext'
import { ChevronRightIcon, ChevronLeftIcon } from 'lucide-react'

export default function DocsLayout({
  children,
}: {
  children: React.ReactNode
}) {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const { fullscreen, docsTreeCollapsed, setDocsTreeCollapsed } = useEditorChrome()
  const canEdit = useCan('doc.edit')

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
                  canEdit={canEdit}
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
