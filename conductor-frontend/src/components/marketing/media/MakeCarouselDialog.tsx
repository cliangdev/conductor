'use client'

// "Make carousel from selection": shows how the selected images group by placement (4:5 / 9:16 / 1:1,
// natural label order) and creates a CAROUSEL Creative. The same rules the server enforces are checked
// first (mediaLibrary.buildCarouselPlan); the server's own 422 violations are shown verbatim.

import { useMemo, useState } from 'react'
import { useRouter } from 'next/navigation'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { apiErrorMessage, type ApiError } from '@/lib/api'
import { CAROUSEL_PLACEMENT_LABEL } from '@/components/marketing/creatives/carouselSlides'
import { createCreative, type CreativePhoto } from '@/components/marketing/creatives/types'
import { buildCarouselPlan } from './mediaSets'

export interface MakeCarouselDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  token: string
  selected: CreativePhoto[]
}

export function MakeCarouselDialog({ open, onOpenChange, projectId, token, selected }: MakeCarouselDialogProps) {
  const plan = useMemo(() => buildCarouselPlan(selected), [selected])
  const [name, setName] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [violations, setViolations] = useState<string[]>([])
  const router = useRouter()

  const effectiveName = name ?? plan.name
  const canCreate = plan.problems.length === 0 && effectiveName.trim().length > 0 && !busy

  async function create() {
    setBusy(true)
    setError(null)
    setViolations([])
    try {
      const slides: Record<string, string[]> = {}
      for (const g of plan.groups) slides[g.placement] = g.items.map((i) => i.id)
      const creative = await createCreative(
        projectId,
        {
          kind: 'CAROUSEL',
          name: effectiveName.trim(),
          slides,
          placements: plan.groups.map((g) => g.placement),
        },
        token,
      )
      router.push(`/app/projects/${projectId}/marketing/creatives/${creative.id}`)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not create the carousel'))
      setViolations(((err as Partial<ApiError>).violations ?? []).map((v) => v.message))
      setBusy(false)
    }
  }

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="Make carousel"
      description="Images are grouped by the placement they fit, in label order."
      footer={
        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" size="sm" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="button" size="sm" disabled={!canCreate} onClick={() => void create()}>
            {busy ? 'Creating…' : 'Create carousel'}
          </Button>
        </div>
      }
    >
      <div className="space-y-4">
        {plan.problems.map((p) => (
          <Alert key={p} variant="destructive">
            {p}
          </Alert>
        ))}
        {error && <Alert variant="destructive">{error}</Alert>}
        {violations.map((v) => (
          <Alert key={v} variant="warning">
            {v}
          </Alert>
        ))}

        <div className="space-y-3">
          {plan.groups.map((g) => (
            <div key={g.placement} data-testid={`carousel-group-${g.placement}`}>
              <p className="text-[11.5px] font-semibold uppercase tracking-wider text-muted-foreground">
                {CAROUSEL_PLACEMENT_LABEL[g.placement]} · {g.items.length} {g.items.length === 1 ? 'slide' : 'slides'}
              </p>
              <ol className="mt-1.5 flex gap-1.5 overflow-x-auto pb-1">
                {g.items.map((item, i) => (
                  <li key={item.id} className="relative h-16 w-16 shrink-0 overflow-hidden rounded-md border border-border bg-surface-3">
                    {item.url && (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img loading="lazy" decoding="async" src={item.url} alt={item.label || `Slide ${i + 1}`} className="h-full w-full object-cover" />
                    )}
                    <span className="absolute left-0.5 top-0.5 rounded bg-foreground/70 px-1 text-[10px] text-background">{i + 1}</span>
                  </li>
                ))}
              </ol>
            </div>
          ))}
        </div>

        <div>
          <Label htmlFor="carousel-name" className="text-xs">
            Name
          </Label>
          <Input id="carousel-name" value={effectiveName} onChange={(e) => setName(e.target.value)} />
        </div>
      </div>
    </Modal>
  )
}
