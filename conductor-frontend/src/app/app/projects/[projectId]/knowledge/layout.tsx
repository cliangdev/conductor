'use client'

import { useParams, usePathname, useRouter } from 'next/navigation'
import { HomeIcon, HistoryIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { KnowledgeSearch } from '@/components/knowledge/KnowledgeSearch'
import { cn } from '@/lib/utils'

export default function KnowledgeLayout({ children }: { children: React.ReactNode }) {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const pathname = usePathname()

  function goToPage(path: string) {
    router.push(`/app/projects/${projectId}/knowledge/page?path=${encodeURIComponent(path)}`)
  }

  const onIndex = pathname === `/app/projects/${projectId}/knowledge`

  return (
    <div className="flex h-full">
      {/* Left rail: search + fixed shortcuts */}
      <div className="w-56 shrink-0 border-r border-border bg-sidebar-bg overflow-y-auto">
        {accessToken && (
          <>
            <KnowledgeSearch projectId={projectId} token={accessToken} onResultSelect={goToPage} />
            <div className="px-1 py-1 space-y-0.5">
              <button
                onClick={() => router.push(`/app/projects/${projectId}/knowledge`)}
                className={cn(
                  'w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-sm text-left transition-colors',
                  onIndex ? 'bg-accent text-accent-foreground font-medium' : 'text-foreground hover:bg-muted'
                )}
              >
                <HomeIcon className="h-3.5 w-3.5 shrink-0 opacity-70" />
                Index
              </button>
              <button
                onClick={() => goToPage('log.md')}
                className="w-full flex items-center gap-2 px-2 py-1.5 rounded-md text-sm text-left text-foreground hover:bg-muted transition-colors"
              >
                <HistoryIcon className="h-3.5 w-3.5 shrink-0 opacity-70" />
                Activity
              </button>
            </div>
          </>
        )}
      </div>

      {/* Right panel: page content */}
      <div className="flex-1 overflow-hidden">{children}</div>
    </div>
  )
}
