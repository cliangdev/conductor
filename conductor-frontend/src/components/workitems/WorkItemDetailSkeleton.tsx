import { Skeleton } from '@/components/ui/skeleton'

/** Shared loading placeholder for the Work Item detail route (both the route-level loading branch
 * and WorkItemDetailView's own loading state render the same shape). */
export function WorkItemDetailSkeleton() {
  return (
    <div className="flex flex-col h-full p-4 md:p-6 gap-4">
      <Skeleton className="h-6 w-64" />
      <Skeleton className="h-4 w-40" />
      <div className="space-y-2 mt-2">
        {[1, 2, 3, 4, 5].map((i) => (
          <Skeleton key={i} className="h-4" style={{ opacity: 1 - i * 0.15 }} />
        ))}
      </div>
    </div>
  )
}
