'use client'

import { useEffect, useRef, useState } from 'react'
import { searchDocs } from '@/lib/docs-api'
import type { DocSearchResult } from '@/lib/docs-api'

interface DocSearchProps {
  projectId: string
  token: string
  onResultSelect: (docId: string) => void
}

function highlightMatch(text: string, query: string): React.ReactNode {
  if (!query) return text
  const lower = text.toLowerCase()
  const queryLower = query.toLowerCase()
  const index = lower.indexOf(queryLower)
  if (index < 0) return text
  return (
    <>
      {text.slice(0, index)}
      <mark className="bg-status-progress/30 text-inherit rounded-sm">{text.slice(index, index + query.length)}</mark>
      {text.slice(index + query.length)}
    </>
  )
}

export function DocSearch({ projectId, token, onResultSelect }: DocSearchProps) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<DocSearchResult[]>([])
  const [searching, setSearching] = useState(false)
  const [searched, setSearched] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current)

    if (query.length < 3) {
      setResults([])
      setSearched(false)
      return
    }

    timerRef.current = setTimeout(async () => {
      setSearching(true)
      try {
        const data = await searchDocs(projectId, query, token)
        setResults(data)
        setSearched(true)
      } catch {
        setResults([])
        setSearched(true)
      } finally {
        setSearching(false)
      }
    }, 300)

    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [query, projectId, token])

  function handleSelect(docId: string) {
    onResultSelect(docId)
    setQuery('')
    setResults([])
    setSearched(false)
  }

  return (
    <div className="px-2 pt-2 pb-1">
      <input
        type="text"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="Search docs..."
        className="w-full px-2 py-1 text-sm rounded border border-border bg-background placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
      />
      {searching && (
        <p className="text-xs text-muted-foreground mt-1 px-1">Searching...</p>
      )}
      {!searching && searched && results.length === 0 && (
        <p className="text-xs text-muted-foreground mt-1 px-1">No docs found.</p>
      )}
      {results.length > 0 && (
        <ul className="mt-1 border border-border rounded bg-background shadow-sm overflow-hidden">
          {results.map((result) => (
            <li key={result.id}>
              <button
                onClick={() => handleSelect(result.id)}
                className="w-full text-left px-2 py-2 hover:bg-accent text-sm"
              >
                <p className="font-medium truncate">{highlightMatch(result.title, query)}</p>
                {result.snippet && (
                  <p className="text-xs text-muted-foreground mt-0.5 line-clamp-2">
                    {highlightMatch(result.snippet, query)}
                  </p>
                )}
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
