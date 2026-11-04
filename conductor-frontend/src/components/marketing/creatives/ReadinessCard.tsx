'use client'

// The Creative page's Readiness card: a status header with the one primary action (Mark ready), what
// blocks the Creative with a fix where the editor has one, what to remember before publishing, and the
// checks that already passed (collapsed). The `state` item is never a row: the header covers it.

import { useState } from 'react'
import { AlertCircleIcon, CheckCircle2Icon, CheckIcon, ChevronDownIcon, TriangleAlertIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { cn } from '@/lib/utils'
import { readinessCopy, sentenceCase } from './readinessCopy'
import type { CreativeReadiness, CreativeReadinessItem, CreativeState } from './types'

export interface ReadinessCardProps {
  readiness: CreativeReadiness
  /** The saved state of the Creative (not the unsaved form). */
  state: CreativeState
  canManage: boolean
  onMarkReady: () => void | Promise<void>
  markingReady?: boolean
  /** In-place fixes by readiness key. A key present here gets its action button on the row. */
  actions?: Record<string, () => void | Promise<void>>
  /** Keys whose action is running (button disabled). */
  busyKeys?: readonly string[]
}

const SECTION_LABEL = 'text-[11.5px] font-semibold uppercase tracking-[0.06em] text-foreground-subtle'

export function ReadinessCard({ readiness, state, canManage, onMarkReady, markingReady, actions, busyKeys }: ReadinessCardProps) {
  const [passedOpen, setPassedOpen] = useState(false)
  const items = readiness.items.filter((i) => i.key !== 'state')
  const toFix = items.filter((i) => !i.ok && i.blocking)
  const warnings = items.filter((i) => !i.ok && !i.blocking)
  const passed = items.filter((i) => i.ok)
  const isReady = state === 'READY'

  return (
    <Card className="divide-y divide-border" data-testid="readiness-card" aria-label="Readiness" role="region">
      <div className="flex flex-wrap items-center gap-3 p-4">
        <span
          aria-hidden
          data-testid="readiness-disc"
          className={cn(
            'flex h-8 w-8 shrink-0 items-center justify-center rounded-full text-[13px] font-semibold',
            toFix.length === 0 ? 'bg-status-approved/15 text-status-approved' : 'bg-destructive/15 text-destructive',
          )}
        >
          {toFix.length === 0 ? <CheckIcon className="h-4 w-4" aria-hidden /> : toFix.length}
        </span>
        <div className="min-w-0 flex-1 basis-48">
          <h3 className="text-[13px] font-semibold text-foreground" data-testid="readiness-title">
            {toFix.length > 0
              ? `${toFix.length} ${toFix.length === 1 ? 'thing' : 'things'} to fix before this can go on a Post`
              : isReady
                ? 'Ready'
                : 'Ready for a Post'}
          </h3>
          <p className="text-[13px] text-muted-foreground" data-testid="readiness-subtitle">
            {toFix.length > 0
              ? 'Fix them here; the Creative is marked ready once nothing blocks.'
              : isReady
                ? 'Nothing blocks this Creative.'
                : 'Nothing blocks this Creative. Mark it ready, or attach it to a Post and it becomes ready on its own.'}
          </p>
        </div>
        {canManage && !isReady && (
          <Button size="sm" disabled={toFix.length > 0 || markingReady} onClick={() => void onMarkReady()}>
            Mark ready
          </Button>
        )}
      </div>

      {toFix.length > 0 && (
        <section className="px-4 py-3" aria-labelledby="readiness-to-fix">
          <h4 id="readiness-to-fix" className={SECTION_LABEL}>To fix</h4>
          <ul className="divide-y divide-border" data-testid="readiness-to-fix">
            {toFix.map((item) => (
              <ReadinessRow key={item.key} item={item} tone="fix" action={canManage ? actions?.[item.key] : undefined} busy={busyKeys?.includes(item.key)} />
            ))}
          </ul>
        </section>
      )}

      {warnings.length > 0 && (
        <section className="px-4 py-3" aria-labelledby="readiness-before-publish">
          <h4 id="readiness-before-publish" className={SECTION_LABEL}>Before you publish</h4>
          <ul className="divide-y divide-border" data-testid="readiness-warnings">
            {warnings.map((item) => (
              <ReadinessRow key={item.key} item={item} tone="warn" action={canManage ? actions?.[item.key] : undefined} busy={busyKeys?.includes(item.key)} />
            ))}
          </ul>
        </section>
      )}

      {passed.length > 0 && (
        <section className="px-4 py-2">
          <div className="flex items-center gap-2 py-1.5">
            <CheckCircle2Icon className="h-4 w-4 shrink-0 text-status-approved" aria-hidden />
            <span className="flex-1 text-[13px] text-muted-foreground">
              {passed.length} {passed.length === 1 ? 'check' : 'checks'} passed
            </span>
            <Button
              variant="ghost"
              size="sm"
              className="h-7 w-7 p-0"
              aria-expanded={passedOpen}
              aria-controls="readiness-passed"
              aria-label={passedOpen ? 'Hide passed checks' : 'Show passed checks'}
              onClick={() => setPassedOpen((o) => !o)}
            >
              <ChevronDownIcon className={cn('h-4 w-4 transition-transform', passedOpen && 'rotate-180')} aria-hidden />
            </Button>
          </div>
          {passedOpen && (
            <ul id="readiness-passed" className="space-y-1 pb-1 pl-6" data-testid="readiness-passed">
              {passed.map((item) => (
                <li key={item.key} className="text-[13px] text-muted-foreground">
                  {sentenceCase(item.message)}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </Card>
  )
}

function ReadinessRow({
  item,
  tone,
  action,
  busy,
}: {
  item: CreativeReadinessItem
  tone: 'fix' | 'warn'
  action?: () => void | Promise<void>
  busy?: boolean
}) {
  const copy = readinessCopy(item)
  const Icon = tone === 'fix' ? AlertCircleIcon : TriangleAlertIcon
  return (
    <li className="flex flex-wrap items-start gap-x-3 gap-y-2 py-2.5" data-testid={`readiness-row-${item.key}`}>
      <Icon className={cn('mt-0.5 h-4 w-4 shrink-0', tone === 'fix' ? 'text-destructive' : 'text-status-progress')} aria-hidden />
      <div className="min-w-0 flex-1 basis-48">
        <p className="text-[13px] font-medium text-foreground">{copy.title}</p>
        {copy.explanation && <p className="text-[13px] text-muted-foreground">{copy.explanation}</p>}
      </div>
      {copy.action && action && (
        <Button variant="outline" size="sm" disabled={busy} onClick={() => void action()}>
          {copy.action}
        </Button>
      )}
    </li>
  )
}
