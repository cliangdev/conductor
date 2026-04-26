'use client'
import { useEffect, useRef, useState } from 'react'
import { useTheme } from 'next-themes'
import { cn } from '@/lib/utils'

interface Props {
  chart: string
  className?: string
}

export function MermaidRenderer({ chart, className }: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const [error, setError] = useState<string | null>(null)
  const { resolvedTheme } = useTheme()

  useEffect(() => {
    let cancelled = false
    const id = 'mermaid-' + Math.random().toString(36).slice(2)
    import('mermaid').then((m) => {
      m.default.initialize({
        startOnLoad: false,
        theme: resolvedTheme === 'dark' ? 'dark' : 'neutral',
        securityLevel: 'loose',
      })
      m.default
        .render(id, chart)
        .then(({ svg }) => {
          if (!cancelled && ref.current) ref.current.innerHTML = svg
        })
        .catch((e: unknown) => {
          if (!cancelled) setError(String(e))
        })
    })
    return () => {
      cancelled = true
    }
  }, [chart, resolvedTheme])

  if (error) {
    return (
      <pre className="text-red-500 text-sm p-3 border border-red-200 rounded bg-red-50 dark:bg-red-950 dark:border-red-800">
        {error}
      </pre>
    )
  }

  return <div ref={ref} className={cn('flex justify-center', className)} />
}
