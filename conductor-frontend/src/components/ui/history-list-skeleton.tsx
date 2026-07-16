import { Skeleton } from '@/components/ui/skeleton'

/** Shared loading placeholder for a version/revision history list (KnowledgeHistoryPanel, DocHistoryPanel). */
export function HistoryListSkeleton() {
  return (
    <div className="px-3 py-3 space-y-3">
      {[0, 1, 2].map((i) => (
        <div key={i} className="space-y-1.5">
          <Skeleton className="h-3 w-16" />
          <Skeleton className="h-3 w-32" />
          <Skeleton className="h-2.5 w-20" />
        </div>
      ))}
    </div>
  )
}
