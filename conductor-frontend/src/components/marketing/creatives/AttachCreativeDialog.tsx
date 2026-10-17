'use client'

// "From a creative" — the Post media strip's way to put a Creative's frames on a saved Post. The same
// picker the compose page uses (CreativesTab: search, Ready/All and kind filters, the per-destination
// size preview, on-demand server render for Clips and Carousels), fed this Post's real destinations.
// Choosing one attaches its render to the Post and says what changed.

import { useState } from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { XIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { toastSuccess } from '@/components/ui/toast'
import { apiErrorMessage } from '@/lib/api'
import { cn } from '@/lib/utils'
import { CreativesTab, useCreatives, type ChooserDestination, type CreativeChoice } from '@/components/marketing/media/CreativesTab'
import { attachCreativeRender } from '@/components/marketing/creatives/types'

export interface AttachCreativeDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  workItemId: string
  token: string
  /** The Post's destinations right now, so the preview shows the size each will get. */
  destinations: ChooserDestination[]
  /** Refetch the Post's assets so the newly attached media appears. */
  onAttached: () => void | Promise<void>
}

function AttachBody({ onOpenChange, projectId, workItemId, token, destinations, onAttached }: AttachCreativeDialogProps) {
  // The body only exists while the dialog is open, so mounting is "opened": load fresh, start clean.
  const { creatives, error: loadError } = useCreatives(projectId, token)
  const [attaching, setAttaching] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function attach(choice: CreativeChoice) {
    setAttaching(true)
    setError(null)
    try {
      const result = await attachCreativeRender(projectId, choice.creative.id, { renderId: choice.renderId, workItemId }, token)
      await onAttached()
      const kept = result.targetsSkipped.length
      toastSuccess(
        kept > 0
          ? `Attached Creative ${choice.creative.displayId} — ${kept} destination${kept === 1 ? ' kept its' : 's kept their'} own media`
          : `Attached Creative ${choice.creative.displayId}`,
      )
      onOpenChange(false)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not attach this Creative'))
    } finally {
      setAttaching(false)
    }
  }

  return (
    <>
      <div className="flex shrink-0 items-start justify-between gap-3 px-4 pt-4 sm:px-5">
        <div className="min-w-0">
          <Dialog.Title className="text-base font-semibold text-foreground">From a creative</Dialog.Title>
          <Dialog.Description className="text-sm text-muted-foreground">
            Pick a Creative — its frames become this Post&rsquo;s media, one matching size per destination.
          </Dialog.Description>
        </div>
        <Button variant="ghost" size="icon" aria-label="Close" onClick={() => onOpenChange(false)}>
          <XIcon className="h-4 w-4" aria-hidden />
        </Button>
      </div>
      {error && (
        <div className="shrink-0 px-4 pt-3 sm:px-5">
          <Alert variant="destructive">{error}</Alert>
        </div>
      )}
      <div className="flex min-h-0 flex-1 flex-col px-4 py-4 sm:px-5">
        <CreativesTab
          creatives={creatives}
          loadError={loadError}
          projectId={projectId}
          token={token}
          destinations={destinations}
          captionEmpty={false}
          offerCaption={false}
          busy={attaching}
          onUse={(choice) => void attach(choice)}
          onCancel={() => onOpenChange(false)}
        />
      </div>
    </>
  )
}

export function AttachCreativeDialog(props: AttachCreativeDialogProps) {
  return (
    <Dialog.Root open={props.open} onOpenChange={props.onOpenChange}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-black/60 dark:bg-black/80" />
        <Dialog.Popup
          className={cn(
            'fixed left-1/2 top-1/2 z-50 -translate-x-1/2 -translate-y-1/2',
            'flex h-[85vh] w-[calc(100vw-2rem)] max-w-[1100px] flex-col overflow-hidden rounded-[10px] border border-border bg-popover shadow-lg outline-none',
          )}
        >
          <AttachBody {...props} />
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
