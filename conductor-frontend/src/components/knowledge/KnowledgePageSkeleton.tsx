import { Skeleton } from '@/components/ui/skeleton'

const BODY_LINE_WIDTHS = ['w-full', 'w-5/6', 'w-2/3']

/** Shared loading placeholder for the knowledge index and knowledge page routes (and their Suspense
 * fallbacks). `fullHeight` wraps in the scrollable full-height shell the index page uses; the page
 * route's inline and Suspense-fallback variants render just the padded content block. */
export function KnowledgePageSkeleton({
  fullHeight = false,
  lines = 3,
}: {
  fullHeight?: boolean
  lines?: number
}) {
  const content = (
    <div
      className={
        fullHeight
          ? 'max-w-4xl mx-auto space-y-3'
          : 'max-w-4xl mx-auto px-4 sm:px-6 lg:px-8 py-6 space-y-3'
      }
    >
      <Skeleton className="h-6 w-48" />
      {BODY_LINE_WIDTHS.slice(0, lines).map((w) => (
        <Skeleton key={w} className={`h-4 ${w}`} />
      ))}
    </div>
  )

  if (!fullHeight) return content
  return <div className="h-full overflow-y-auto px-4 sm:px-6 lg:px-8 py-6">{content}</div>
}
