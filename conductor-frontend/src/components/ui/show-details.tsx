'use client'

import { useState } from 'react'
import { Button } from '@/components/ui/button'

/**
 * A "Show details" disclosure beside a message, for the extra detail the server sends alongside it —
 * only rendered when there is one and it says more than the message already does.
 */
export function ShowDetails({ message, detail }: { message: string | null | undefined; detail?: string | null }) {
  const [open, setOpen] = useState(false)
  if (!detail || detail === message) return null
  return (
    <div>
      <Button variant="link" size="sm" className="h-auto p-0 text-xs" onClick={() => setOpen((o) => !o)}>
        {open ? 'Hide details' : 'Show details'}
      </Button>
      {open && (
        <pre className="mt-1 max-w-full overflow-x-auto whitespace-pre-wrap rounded-md border border-border bg-surface-2 px-2 py-1.5 text-xs text-foreground">
          {detail}
        </pre>
      )}
    </div>
  )
}
