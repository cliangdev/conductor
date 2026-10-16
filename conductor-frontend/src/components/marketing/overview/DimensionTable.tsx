'use client'

// One breakdown table on the channel detail page (referrers, queries, campaigns, platforms). Click a
// column heading to sort; the table scrolls inside its own container on narrow screens.

import { useMemo, useState } from 'react'
import { ArrowDownIcon, ArrowUpIcon } from 'lucide-react'
import { formatMetricValue } from './format'
import type { OverviewDimension } from './types'

type SortKey = 'label' | number

export function DimensionTable({ dimension }: { dimension: OverviewDimension }) {
  const [sortKey, setSortKey] = useState<SortKey>(0)
  const [desc, setDesc] = useState(true)

  const rows = useMemo(() => {
    const sorted = [...dimension.rows]
    sorted.sort((a, b) => {
      if (sortKey === 'label') return a.label.localeCompare(b.label)
      const av = a.values[sortKey]
      const bv = b.values[sortKey]
      if (av === null || av === undefined) return bv === null || bv === undefined ? 0 : 1
      if (bv === null || bv === undefined) return -1
      return av - bv
    })
    // Rows without a value always sink to the bottom regardless of direction.
    return desc ? sorted.reverse().sort((a, b) => nullLast(a, b, sortKey)) : sorted
  }, [dimension.rows, sortKey, desc])

  function toggle(key: SortKey) {
    if (key === sortKey) setDesc((d) => !d)
    else {
      setSortKey(key)
      setDesc(key !== 'label')
    }
  }

  function header(key: SortKey, label: string, align: 'left' | 'right') {
    const active = sortKey === key
    return (
      <th
        scope="col"
        aria-sort={active ? (desc ? 'descending' : 'ascending') : 'none'}
        className={`px-3 py-2 font-medium ${align === 'right' ? 'text-right' : 'text-left'}`}
      >
        <button
          type="button"
          onClick={() => toggle(key)}
          className="inline-flex items-center gap-1 rounded-sm hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {label}
          {active && (desc ? <ArrowDownIcon className="h-3 w-3" aria-hidden /> : <ArrowUpIcon className="h-3 w-3" aria-hidden />)}
        </button>
      </th>
    )
  }

  return (
    <section aria-label={dimension.label} className="space-y-2">
      <h3 className="text-sm font-semibold text-foreground">{dimension.label}</h3>
      {rows.length === 0 ? (
        <p className="rounded-lg border border-border bg-card px-4 py-6 text-sm text-muted-foreground">
          No breakdown yet. It appears once this channel reports data for the window.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-border bg-card">
          <table className="w-full text-sm">
            <thead className="bg-muted/50 text-xs text-muted-foreground">
              <tr>
                {header('label', 'Name', 'left')}
                {dimension.columns.map((c, i) => (
                  <th key={c.key} scope="col" aria-sort={sortKey === i ? (desc ? 'descending' : 'ascending') : 'none'} className="px-3 py-2 text-right font-medium">
                    <button
                      type="button"
                      onClick={() => toggle(i)}
                      className="inline-flex items-center gap-1 rounded-sm hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    >
                      {c.label}
                      {sortKey === i && (desc ? <ArrowDownIcon className="h-3 w-3" aria-hidden /> : <ArrowUpIcon className="h-3 w-3" aria-hidden />)}
                    </button>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {rows.map((row) => (
                <tr key={row.key}>
                  <td className="max-w-[16rem] truncate px-3 py-2 text-foreground" title={row.label}>
                    {row.label}
                  </td>
                  {dimension.columns.map((c, i) => (
                    <td key={c.key} className="px-3 py-2 text-right tabular-nums text-foreground">
                      {formatMetricValue(row.values[i], c.unit)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function nullLast(
  a: { values: (number | null)[] },
  b: { values: (number | null)[] },
  key: SortKey,
): number {
  if (key === 'label') return 0
  const an = a.values[key] === null || a.values[key] === undefined
  const bn = b.values[key] === null || b.values[key] === undefined
  return an === bn ? 0 : an ? 1 : -1
}
