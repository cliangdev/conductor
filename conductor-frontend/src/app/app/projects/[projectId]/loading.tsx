import { PageContainer } from '@/components/layout/PageContainer'
import { Skeleton } from '@/components/ui/skeleton'

// Shown inside the project layout while a page's route segment loads, so a soft navigation paints the
// chrome (sidebar, navbar) and a placeholder at once instead of holding the old page until the server
// has rendered the next one.
export default function ProjectLoading() {
  return (
    <PageContainer>
      <div className="space-y-4" aria-busy="true" aria-label="Loading">
        <Skeleton className="h-4 w-40" />
        <Skeleton className="h-7 w-72" />
        <div className="space-y-2 pt-2">
          {[1, 2, 3, 4].map((i) => (
            <Skeleton key={i} className="h-4" style={{ opacity: 1 - i * 0.15 }} />
          ))}
        </div>
      </div>
    </PageContainer>
  )
}
