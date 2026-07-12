'use client'

import { useEffect, useRef, useState } from 'react'
import { searchKnowledge } from '@/lib/knowledge-api'
import type { KnowledgeSearchHit } from '@/lib/knowledge-api'

interface KnowledgeSearchProps {
  projectId: string
  token: string
  onResultSelect: (path: string) => void
}

/** Strips ts_headline's <b>/</b> markers (and any other tags, defensively) back to plain text. */
function stripHighlightTags(text: string): string {
  return text.replace(/<[^>]+>/g, '')
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
      <mark className="bg-yellow-200 text-inherit rounded-sm">{text.slice(index, index + query.length)}</mark>
      {text.slice(index + query.length)}
    </>
  )
}

export function KnowledgeSearch({ projectId, token, onResultSelect }: KnowledgeSearchProps) {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<KnowledgeSearchHit[]>([])
  const [searching, setSearching] = useState(false)
  const [searched, setSearched] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (timerRef.current) clearTimeout(timerRef.current)

    if (query.trim().length < 2) {
      setResults([])
      setSearched(false)
      return
    }

    timerRef.current = setTimeout(async () => {
      setSearching(true)
      try {
        const data = await searchKnowledge(projectId, query, token, { limit: 20 })
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

  function handleSelect(path: string) {
    onResultSelect(path)
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
        placeholder="Search knowledge..."
        className="w-full px-2 py-1 text-sm rounded border border-border bg-background placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring"
      />
      {searching && (
        <p className="text-xs text-muted-foreground mt-1 px-1">Searching...</p>
      )}
      {!searching && searched && results.length === 0 && (
        <p className="text-xs text-muted-foreground mt-1 px-1">No pages found.</p>
      )}
      {results.length > 0 && (
        <ul className="mt-1 border border-border rounded bg-background shadow-sm overflow-hidden max-h-96 overflow-y-auto">
          {results.map((hit) => {
            const snippet = hit.snippet ? stripHighlightTags(hit.snippet) : null
            return (
              <li key={hit.path}>
                <button
                  onClick={() => handleSelect(hit.path)}
                  className="w-full text-left px-2 py-2 hover:bg-accent text-sm"
                >
                  <div className="flex items-center gap-1.5">
                    <p className="font-medium truncate flex-1">
                      {highlightMatch(hit.title ?? hit.path, query)}
                    </p>
                    <span className="text-[10px] px-1 py-0.5 rounded bg-muted text-muted-foreground shrink-0">
                      {hit.type}
                    </span>
                  </div>
                  <p className="text-xs text-muted-foreground truncate">{hit.path}</p>
                  {snippet && (
                    <p className="text-xs text-muted-foreground mt-0.5 line-clamp-2">
                      {highlightMatch(snippet, query)}
                    </p>
                  )}
                </button>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
