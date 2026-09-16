// The "By platform" / "By format" compact tables — one row per InsightsGroup.

import { PlatformIcon } from '@/components/marketing/destinations/PlatformIcon'
import type { PublishPlatform } from '@/components/marketing/destinations/types'
import { compactCount, count } from '@/components/marketing/destinations/DestinationMetrics'
import { humanizeId } from '@/lib/workflows'
import { avgViewPctLabel, engagementRateLabel, type InsightsGroup } from './types'

const PLATFORMS: ReadonlySet<string> = new Set<PublishPlatform>(['facebook', 'instagram', 'youtube', 'tiktok'])

function groupLabel(group: InsightsGroup): string {
  if (group.label) return group.label
  return humanizeId(group.key)
}

/** A compact key/label table: posts, views, engagement rate, and avg view % when any row has it. */
export function InsightsGroupTable({
  title,
  groups,
  rowKind,
  emptyMessage,
}: {
  title: string
  groups: InsightsGroup[]
  /** 'platform' renders a PlatformIcon ahead of the label; other kinds render the label alone. */
  rowKind: 'platform' | 'format'
  emptyMessage: string
}) {
  const showAvgViewPct = groups.some((g) => g.avgViewPct !== null && g.avgViewPct !== undefined)

  return (
    <div>
      <h3 className="mb-2 text-sm font-semibold text-foreground">{title}</h3>
      {groups.length === 0 ? (
        <p className="text-sm text-muted-foreground">{emptyMessage}</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
                <th className="py-1 pr-3 font-medium">{rowKind === 'platform' ? 'Platform' : 'Format'}</th>
                <th className="py-1 pr-3 text-right font-medium">Posts</th>
                <th className="py-1 pr-3 text-right font-medium">Views</th>
                <th className="py-1 pr-3 text-right font-medium">Engagement rate</th>
                {showAvgViewPct && <th className="py-1 pr-3 text-right font-medium">Avg view %</th>}
              </tr>
            </thead>
            <tbody>
              {groups.map((group) => (
                <tr key={group.key} className="border-t border-border">
                  <td className="py-1.5 pr-3 text-foreground">
                    <span className="flex items-center gap-2">
                      {rowKind === 'platform' && PLATFORMS.has(group.key) && (
                        <PlatformIcon platform={group.key as PublishPlatform} />
                      )}
                      {groupLabel(group)}
                    </span>
                  </td>
                  <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">{count(group.posts)}</td>
                  <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">{compactCount(group.views)}</td>
                  <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">
                    {engagementRateLabel(group.engagementRate)}
                  </td>
                  {showAvgViewPct && (
                    <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">
                      {avgViewPctLabel(group.avgViewPct)}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
