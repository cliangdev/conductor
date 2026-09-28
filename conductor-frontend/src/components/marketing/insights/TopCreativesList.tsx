// "Top creatives" — one row per Creative variant, best views first, linking to its editor page
// (COND-24 T5). Pattern: InsightsPostList.tsx.

import Link from 'next/link'
import { compactCount } from '@/components/marketing/destinations/DestinationMetrics'
import type { CreativePerformanceEntry } from './types'
import { avgViewPctLabel, engagementRateLabel } from './types'

export function TopCreativesList({
  creatives,
  hrefFor,
}: {
  creatives: CreativePerformanceEntry[]
  hrefFor: (creative: CreativePerformanceEntry) => string
}) {
  if (creatives.length === 0) return null

  const showAvgViewPct = creatives.some((c) => c.avgViewPct !== null && c.avgViewPct !== undefined)

  return (
    <div>
      <h3 className="mb-2 text-sm font-semibold text-foreground">Top creatives</h3>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
              <th className="py-1 pr-3 font-medium">Creative</th>
              <th className="py-1 pr-3 font-medium">Headline</th>
              <th className="py-1 pr-3 text-right font-medium">Posts</th>
              <th className="py-1 pr-3 text-right font-medium">Views</th>
              <th className="py-1 pr-3 text-right font-medium">Engagement rate</th>
              {showAvgViewPct && <th className="py-1 pr-3 text-right font-medium">Avg view %</th>}
            </tr>
          </thead>
          <tbody>
            {creatives.map((creative) => (
              <tr key={creative.creativeId} className="border-t border-border">
                <td className="py-1.5 pr-3">
                  <Link href={hrefFor(creative)} className="font-mono text-foreground hover:underline">
                    {creative.label}
                  </Link>
                </td>
                <td className="max-w-xs truncate py-1.5 pr-3 text-muted-foreground">{creative.headline ?? '—'}</td>
                <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">{creative.posts}</td>
                <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">{compactCount(creative.views)}</td>
                <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">
                  {engagementRateLabel(creative.engagementRate)}
                </td>
                {showAvgViewPct && (
                  <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">
                    {avgViewPctLabel(creative.avgViewPct)}
                  </td>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
