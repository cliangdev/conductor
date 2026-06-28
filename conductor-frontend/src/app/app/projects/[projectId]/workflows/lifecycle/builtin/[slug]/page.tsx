'use client'

// COND-18: read-only view of a built-in Workflow (e.g. ENGINEERING). Built-ins have no DB row, so
// they can't be edited or published — instead they double as templates: "Clone to project" opens the
// new-workflow editor seeded from this built-in's definition.

import { useEffect, useMemo, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { Can } from '@/components/auth/Can'
import { fetchWorkflowView, categoryVariant, humanizeId } from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'
import type { StatechartStatus, StatechartTransition } from '@/lib/workflowDefinition'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { StatechartDiagram } from '@/components/workflow/lifecycle/StatechartDiagram'

export default function BuiltinWorkflowPage() {
  const { projectId, slug } = useParams<{ projectId: string; slug: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const [view, setView] = useState<WorkflowView | null>(null)
  const [loaded, setLoaded] = useState(false)

  const base = `/app/projects/${projectId}/workflows`

  useEffect(() => {
    if (!accessToken) return
    fetchWorkflowView(projectId, slug, accessToken)
      .then(setView)
      .catch(() => {})
      .finally(() => setLoaded(true))
  }, [projectId, slug, accessToken])

  const statuses: StatechartStatus[] = useMemo(
    () =>
      (view?.statuses ?? []).map((s) => ({
        id: s.id,
        label: s.label,
        category: (s.category as StatechartStatus['category']) ?? 'open',
        initial: s.initial,
        terminal: s.terminal,
      })),
    [view],
  )
  const transitions: StatechartTransition[] = useMemo(
    () =>
      (view?.transitions ?? []).map((t) => ({
        from: t.from,
        to: t.to,
        label: t.label,
        requiresReview: t.requiresReview,
      })),
    [view],
  )

  if (!loaded) {
    return (
      <PageContainer>
        <PageHeader title="Workflow" breadcrumbs={[{ label: 'Workflows', href: base }]} />
        <p className="text-muted-foreground">Loading…</p>
      </PageContainer>
    )
  }

  if (!view) {
    return (
      <PageContainer>
        <PageHeader title="Workflow" breadcrumbs={[{ label: 'Workflows', href: base }]} />
        <p className="text-sm text-destructive">Workflow not found.</p>
      </PageContainer>
    )
  }

  return (
    <PageContainer>
      <PageHeader
        title={view.slug}
        breadcrumbs={[{ label: 'Workflows', href: base }, { label: view.slug }]}
        status={<Badge variant="secondary">Built-in</Badge>}
        description={`Noun: ${view.noun} · ${view.types.length} type(s) · v${view.version}`}
        actions={
          <Can do="workflow.manage">
            <Button onClick={() => router.push(`${base}/lifecycle/new?from=${view.slug}`)}>
              Clone to project
            </Button>
          </Can>
        }
      />

      <div className="grid grid-cols-1 lg:grid-cols-[320px_1fr] gap-4">
        <div className="space-y-4">
          <section className="border border-border rounded-lg p-4">
            <h3 className="text-sm font-semibold mb-2">Statuses</h3>
            <ul className="space-y-1.5">
              {view.statuses.map((s) => (
                <li key={s.id} className="flex items-center gap-2">
                  <Badge variant={categoryVariant(s.category)}>{s.label || humanizeId(s.id)}</Badge>
                  {s.initial && <span className="text-[10px] uppercase text-muted-foreground">initial</span>}
                  {s.terminal && <span className="text-[10px] uppercase text-muted-foreground">terminal</span>}
                </li>
              ))}
            </ul>
          </section>

          <section className="border border-border rounded-lg p-4">
            <h3 className="text-sm font-semibold mb-2">Transitions</h3>
            <ul className="space-y-1.5 text-sm">
              {view.transitions.map((t, i) => (
                <li key={i} className="flex items-center gap-1.5 flex-wrap">
                  <span className="font-mono text-xs text-muted-foreground">{t.from}</span>
                  <span className="text-muted-foreground">→</span>
                  <span className="font-mono text-xs text-muted-foreground">{t.to}</span>
                  <span className="text-xs">{t.label}</span>
                  {t.requiresReview && (
                    <span className="text-[10px] uppercase text-amber-600">review</span>
                  )}
                </li>
              ))}
            </ul>
          </section>
        </div>

        <div className="border border-border rounded-lg overflow-hidden h-[480px]">
          <div className="px-4 py-2 border-b border-border bg-muted/30">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Diagram</p>
          </div>
          <div className="h-[calc(100%-37px)]">
            <StatechartDiagram statuses={statuses} transitions={transitions} />
          </div>
        </div>
      </div>
    </PageContainer>
  )
}
