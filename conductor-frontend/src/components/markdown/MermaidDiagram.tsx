'use client'
import { useEffect, useRef, useState } from 'react'

interface Props {
  chart: string
}

export function MermaidDiagram({ chart }: Props) {
  const ref = useRef<HTMLDivElement>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const id = 'mermaid-' + Math.random().toString(36).slice(2)
    import('mermaid').then((m) => {
      m.default.initialize({
        startOnLoad: false,
        theme: 'neutral',
        securityLevel: 'loose',
      })
      m.default
        .render(id, chart)
        .then(({ svg }) => {
          if (ref.current) ref.current.innerHTML = svg
        })
        .catch((e: unknown) => setError(String(e)))
    })
  }, [chart])

  if (error) {
    return (
      <pre className="text-red-500 text-sm p-3 border border-red-200 rounded bg-red-50 dark:bg-red-950 dark:border-red-800">
        {error}
      </pre>
    )
  }

  return <div ref={ref} className="my-4 flex justify-center overflow-x-auto" />
}
