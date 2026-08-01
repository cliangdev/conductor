'use client'
import { useEffect, useRef, useState } from 'react'
import { useTheme } from 'next-themes'
import { cn } from '@/lib/utils'

interface Props {
  chart: string
  className?: string
  onRendered?: (svg: SVGSVGElement) => void
}

export function MermaidRenderer({ chart, className, onRendered }: Props) {
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
          if (!cancelled && ref.current) {
            ref.current.innerHTML = svg
            const svgEl = ref.current.querySelector('svg')
            if (svgEl) onRendered?.(svgEl as unknown as SVGSVGElement)
          }
        })
        .catch((e: unknown) => {
          if (!cancelled) setError(String(e))
        })
    })
    return () => {
      cancelled = true
    }
  }, [chart, resolvedTheme, onRendered])

  if (error) {
    return (
      <pre className="text-status-failed text-sm p-3 border border-status-failed/30 rounded bg-status-failed/10">
        {error}
      </pre>
    )
  }

  return <div ref={ref} className={cn('flex justify-center', className)} />
}
