'use client'

// "N posts need TikTok consent" — a link on the Posts list to the bulk consent page. The count comes from
// the database-only summary endpoint (TikTok is never called). It is a nicety, so any failure leaves
// the list without it.

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ShieldCheck } from 'lucide-react'
import { fetchConsentSummary } from './api'

export function PendingConsentNotice({ projectId, token }: { projectId: string; token: string }) {
  const [count, setCount] = useState(0)

  useEffect(() => {
    let cancelled = false
    fetchConsentSummary(projectId, token)
      .then((summary) => {
        if (cancelled || typeof summary?.pendingCount !== 'number') return
        setCount(summary.pendingCount)
      })
      .catch(() => {
        if (!cancelled) setCount(0)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token])

  if (count === 0) return null
  return (
    <Link
      href={`/app/projects/${projectId}/marketing/consent`}
      className="mb-4 flex items-center gap-2 rounded-md border border-border bg-surface-raised px-3 py-2 text-sm text-foreground hover:bg-muted"
    >
      <ShieldCheck className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
      {count} {count === 1 ? 'post needs' : 'posts need'} TikTok consent
    </Link>
  )
}
