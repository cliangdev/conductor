import { Skeleton } from '@/components/ui/skeleton'

/**
 * Shared loading placeholder for the Work Item list route — both the route-level loading branch
 * (page.tsx, while resolving the area/noun → Workflow) and WorkItemListView's own loading state
 * render this same shape, mirroring how WorkItemDetailSkeleton is shared for the detail route.
 * Rows approximate the 38px grouped-row anatomy: ring · id · title · trailing meta.
 */
export function WorkItemListSkeleton() {
  return (
    <div>
      <Skeleton className="h-9 w-48 mb-6" />
      <div className="border border-border rounded-lg overflow-hidden divide-y divide-border">
        {[1, 2, 3, 4, 5, 6].map((i) => (
          <div key={i} className="flex items-center gap-3 h-[38px] px-3" style={{ opacity: 1 - i * 0.1 }}>
            <Skeleton className="w-3.5 h-3.5 rounded-full shrink-0" />
            <Skeleton className="h-3 w-14 shrink-0" />
            <Skeleton className="h-3 flex-1 max-w-xs" />
            <Skeleton className="h-3 w-10 ml-auto shrink-0" />
            <Skeleton className="h-5 w-5 rounded-full shrink-0" />
            <Skeleton className="h-3 w-12 shrink-0" />
          </div>
        ))}
      </div>
    </div>
  )
}
