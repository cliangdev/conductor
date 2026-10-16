'use client'

// Client-side search / filter / sort for a list of media items, shared by the Media library page and
// the media chooser. The whole library is held in memory and searched by label substring: the server's
// labelPrefix filter can't serve "slide 03" matching "01-import 4x5 slide 03".

import { useMemo, useState } from 'react'
import type { CreativePhoto } from '@/components/marketing/creatives/types'
import { sortNatural } from './mediaSets'

export type KindTab = 'ALL' | 'IMAGE' | 'VIDEO' | 'AUDIO'
export type MediaSort = 'newest' | 'name'
export type MediaChip = 'unchecked' | 'blocked' | 'ai' | 'onPosts' | 'unused'

export interface MediaFilters {
  kind: KindTab
  setKind: (k: KindTab) => void
  query: string
  setQuery: (q: string) => void
  sort: MediaSort
  setSort: (s: MediaSort) => void
  chips: Record<MediaChip, boolean>
  toggleChip: (c: MediaChip) => void
  /** True when any search, chip or kind tab narrows the list (drives "Nothing matches" copy). */
  filtering: boolean
  visible: CreativePhoto[]
}

const NO_CHIPS: Record<MediaChip, boolean> = { unchecked: false, blocked: false, ai: false, onPosts: false, unused: false }

export function filterAndSortMedia(
  items: CreativePhoto[],
  f: { kind: KindTab; query: string; sort: MediaSort; chips: Record<MediaChip, boolean> },
): CreativePhoto[] {
  const q = f.query.trim().toLowerCase()
  const { chips } = f
  const sorted =
    f.sort === 'name'
      ? sortNatural(items)
      : [...items].sort((a, b) => b.createdAt.localeCompare(a.createdAt) || a.id.localeCompare(b.id))
  return sorted.filter(
    (i) =>
      (f.kind === 'ALL' || (i.mediaKind ?? 'IMAGE') === f.kind) &&
      (!q || (i.label ?? '').toLowerCase().includes(q)) &&
      (!chips.unchecked || !i.checked) &&
      (!chips.ai || i.aiGenerated) &&
      (!chips.onPosts || (i.postCount ?? 0) > 0) &&
      (!chips.unused || ((i.postCount ?? 0) === 0 && (i.creativeCount ?? 0) === 0)) &&
      (chips.blocked || !i.blocked),
  )
}

export function useMediaFilters(items: CreativePhoto[] | null, initial: { sort?: MediaSort; kind?: KindTab } = {}): MediaFilters {
  const [kind, setKind] = useState<KindTab>(initial.kind ?? 'ALL')
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<MediaSort>(initial.sort ?? 'name')
  const [chips, setChips] = useState(NO_CHIPS)

  const visible = useMemo(() => filterAndSortMedia(items ?? [], { kind, query, sort, chips }), [items, kind, query, sort, chips])
  const filtering = query.trim() !== '' || kind !== 'ALL' || chips.unchecked || chips.ai || chips.onPosts || chips.unused

  return {
    kind,
    setKind,
    query,
    setQuery,
    sort,
    setSort,
    chips,
    toggleChip: (c) => setChips((prev) => ({ ...prev, [c]: !prev[c] })),
    filtering,
    visible,
  }
}
