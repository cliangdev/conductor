'use client'

// "N posts need TikTok consent" — a link on the Posts list to the bulk consent page. The count comes from
// the same page read the consent page uses (no `request`: every pending Post). It is a nicety, so any
// failure — including a 403 for a credential that isn't a person — leaves the list without it.

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ShieldCheck } from 'lucide-react'
import { fetchConsentPage } from './api'

export function PendingConsentNotice({ projectId, token }: { projectId: string; token: string }) {
  const [count, setCount] = useState(0)

  useEffect(() => {
    let cancelled = false
    fetchConsentPage(projectId, token)
      .then((page) => {
        if (cancelled || !Array.isArray(page?.posts)) return
        setCount(page.posts.filter((p) => p.verdict !== 'VALID').length)
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
