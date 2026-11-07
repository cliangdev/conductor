'use client'

// Open marketing Changes (PLANNED or LIVE) on the Marketing overview, soonest "Review on" first. The Changes
// lifecycle is hidden from the sidebar, so this is where people find it. Renders nothing when there are none
// (or the read fails): it is a bonus on the page, never an error or an empty state.

import Link from 'next/link'
import { useEffect, useState } from 'react'
import { StatusBadge } from '@/components/ui/status-badge'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet } from '@/lib/api'
import { workItemDetailPath, workItemListPath } from '@/lib/workflows'
import { shortDate } from './format'

const SLUG = 'MARKETING_CHANGES'
const AREA = 'MARKETING'
const NOUN = 'Change'
const OPEN_CATEGORY: Record<string, string> = { PLANNED: 'open', LIVE: 'in_progress' }
/** An imported change log can hold dozens of open Changes; the card shows the soonest few, the list has the rest. */
const MAX_ROWS = 5

interface OpenChange {
  id: string
  displayId?: string
  title: string
  status: string
  dueOn?: string | null
}

function today(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

export function OpenChangesCard({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth()
  const [changes, setChanges] = useState<OpenChange[]>([])

  useEffect(() => {
    if (!accessToken) return
    let cancelled = false
    apiGet<OpenChange[]>(`/api/v2/projects/${projectId}/work-items?workflow=${SLUG}&sort=dueOn`, accessToken)
      .then((items) => {
        if (cancelled || !Array.isArray(items)) return
        setChanges(items.filter((i) => i.status in OPEN_CATEGORY && !!i.displayId))
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken])

  if (changes.length === 0) return null

  const now = today()
  const shown = changes.slice(0, MAX_ROWS)
  return (
    <section aria-label="Open changes" className="rounded-lg border border-border bg-card p-4">
      <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-foreground">Open changes</h2>
        <Link
          prefetch={false}
          href={workItemListPath(projectId, AREA, NOUN)}
          className="rounded-sm text-xs font-medium text-muted-foreground underline-offset-2 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {changes.length > MAX_ROWS ? `See all ${changes.length}` : 'See all'}
        </Link>
      </header>
      <ul className="divide-y divide-border">
        {shown.map((c) => {
          const overdue = !!c.dueOn && c.dueOn < now
          const due = shortDate(c.dueOn)
          return (
            <li key={c.id} className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1 py-2 first:pt-0 last:pb-0">
              <Link
                prefetch={false}
                href={workItemDetailPath(projectId, AREA, NOUN, c.displayId as string)}
                className="min-w-0 truncate rounded-sm text-sm text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {c.title}
              </Link>
              <div className="flex shrink-0 items-center gap-2">
                {due && (
                  <span className={`text-xs ${overdue ? 'font-medium text-status-failed' : 'text-muted-foreground'}`}>
                    Review on {due}
                    {overdue && ' · overdue'}
                  </span>
                )}
                <StatusBadge status={c.status} category={OPEN_CATEGORY[c.status]} />
              </div>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
