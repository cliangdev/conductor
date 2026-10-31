'use client'

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { ChevronDown, ExternalLink, FileText } from 'lucide-react'
import { CommentCount } from '@/components/ui/comment-count'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'

export const TAB_CLASSES =
  'inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium border-b-2 -mb-px transition-colors whitespace-nowrap'

export function tabStateClasses(active: boolean) {
  return active ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground'
}

/**
 * Unresolved comment count on a tab — without it there's no way to tell which of an item's documents
 * someone commented on short of opening each and scanning its gutter. The count is repeated in the
 * tab's accessible name, so this copy is `aria-hidden` and never the sole carrier.
 */
export function tabCommentCount(count: number, active: boolean) {
  return (
    <span aria-hidden="true">
      <CommentCount count={count} className={active ? 'text-primary' : 'text-muted-foreground'} />
    </span>
  )
}

export function commentSuffix(count: number): string {
  return count > 0 ? `, ${count} unresolved comment${count !== 1 ? 's' : ''}` : ''
}

export interface DocumentTab {
  id: string
  filename: string
  /** Unresolved comments on the document. */
  commentCount: number
  /** Opens in a new tab instead of rendering here (a binary file). */
  external: boolean
}

// The strip lays tabs out with gap-1; each measured width carries its share so the sums stay honest.
const TAB_GAP = 4

/**
 * Which tabs fit in `available` pixels, in their original order (the "priority+" pattern GitHub's
 * UnderlineNav uses). When they don't all fit, room is kept for the "+N more" tab, tabs are taken from
 * the start until the next one would overflow, and `keep` (the open document) is always shown, so the
 * selection is never hidden in the menu.
 */
export function fitTabs(
  widths: number[],
  available: number,
  moreWidth: number,
  keep: number,
): { shown: number[]; hidden: number[] } {
  const all = widths.map((_, i) => i)
  if (widths.reduce((sum, w) => sum + w, 0) <= available) return { shown: all, hidden: [] }
  const shown = new Set<number>(keep >= 0 ? [keep] : [])
  let used = moreWidth + (keep >= 0 ? widths[keep] : 0)
  for (const i of all) {
    if (i === keep) continue
    if (used + widths[i] > available) break
    used += widths[i]
    shown.add(i)
  }
  return { shown: all.filter((i) => shown.has(i)), hidden: all.filter((i) => !shown.has(i)) }
}

/**
 * Left/Right arrow handling for a `role="tablist"` (WAI-ARIA tabs pattern, automatic activation):
 * moves focus across the tabs that are displayed, wrapping at the ends, and activates the one it lands
 * on. Tabs are found in the DOM, so tabs that collapse into a "+N more" menu, or that CSS hides at this
 * breakpoint, are skipped without the caller tracking them. A tab with no `data-tab-id` (the "+N more"
 * tab) is focused but not activated.
 */
export function handleTabListKeyDown(e: React.KeyboardEvent<HTMLElement>, activate: (tabId: string) => void) {
  if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
  const tabs = Array.from(e.currentTarget.querySelectorAll<HTMLElement>('[role="tab"]')).filter(
    (el) => getComputedStyle(el).display !== 'none',
  )
  const idx = tabs.indexOf(e.target as HTMLElement)
  if (idx < 0) return
  e.preventDefault()
  const next = tabs[(idx + (e.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length]
  next.focus()
  if (next.dataset.tabId) activate(next.dataset.tabId)
}

// useLayoutEffect warns during server rendering; measuring only matters in the browser.
const useBrowserLayoutEffect = typeof window === 'undefined' ? useEffect : useLayoutEffect

/** "+3 more" with the hidden documents' comments; just "+3" on a phone, where the open tab needs the room. */
function MoreLabel({ count, comments }: { count: number; comments: number }) {
  return (
    <>
      +{count}
      <span className="hidden sm:inline">more</span>
      <span className="hidden sm:inline">{tabCommentCount(comments, false)}</span>
      <ChevronDown className="h-3.5 w-3.5" aria-hidden="true" />
    </>
  )
}

interface Props {
  documents: DocumentTab[]
  /** The document whose tab is selected, or null when another tab (Activity) is. */
  activeId: string | null
  /** The document that must stay visible when tabs collapse. Defaults to `activeId`. */
  keepVisibleId?: string | null
  onSelect: (documentId: string) => void
  /** The DOM id of a document's tab — the panel's `aria-labelledby` points at it. */
  tabId: (documentId: string) => string
  /** The id of the tabpanel these tabs control. */
  panelId: string
}

/**
 * A Work Item's document tabs, for use inside a `role="tablist"`. Shows as many tabs as fit and puts
 * the rest behind a "+N more" tab whose menu lists every document, so it's always clear how many there
 * are. Fills the remaining width of its row; the caller places Activity and other fixed tabs after it.
 */
export function DocumentTabs({ documents, activeId, keepVisibleId = activeId, onSelect, tabId, panelId }: Props) {
  const stripRef = useRef<HTMLDivElement>(null)
  const tabRefs = useRef(new Map<string, HTMLElement>())
  const moreRef = useRef<HTMLSpanElement>(null)
  const [available, setAvailable] = useState(Infinity)
  const [measured, setMeasured] = useState<{ key: string; widths: Map<string, number>; more: number } | null>(null)

  // Tab widths change only with the tabs' text, so they're measured once per set of tabs (every tab
  // rendered, read before paint), then reused as the strip resizes.
  const measureKey = documents.map((d) => `${d.id}\u0000${d.filename}\u0000${d.commentCount}\u0000${d.external}`).join('\u0001')
  const measuring = measured?.key !== measureKey

  useBrowserLayoutEffect(() => {
    if (!measuring) return
    const widths = new Map(documents.map((d) => [d.id, (tabRefs.current.get(d.id)?.offsetWidth ?? 0) + TAB_GAP]))
    setMeasured({ key: measureKey, widths, more: (moreRef.current?.offsetWidth ?? 0) + TAB_GAP })
  }, [measuring, measureKey, documents])

  const hasDocuments = documents.length > 0
  useBrowserLayoutEffect(() => {
    const el = stripRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(([entry]) => setAvailable(entry.contentRect.width))
    ro.observe(el)
    return () => ro.disconnect()
  }, [hasDocuments])

  // A web font that finishes loading after the first measure changes every width, and so does
  // crossing the breakpoint where "+N more" drops its word and count.
  useEffect(() => {
    let live = true
    document.fonts?.ready.then(() => {
      if (live) setMeasured(null)
    })
    const wide = window.matchMedia?.('(min-width: 640px)')
    const remeasure = () => setMeasured(null)
    wide?.addEventListener('change', remeasure)
    return () => {
      live = false
      wide?.removeEventListener('change', remeasure)
    }
  }, [])

  if (!hasDocuments) return null

  const keep = keepVisibleId ? documents.findIndex((d) => d.id === keepVisibleId) : -1
  const { shown, hidden } = measuring
    ? { shown: documents.map((_, i) => i), hidden: [] as number[] }
    : fitTabs(documents.map((d) => measured.widths.get(d.id) ?? 0), available, measured.more, keep)
  const hiddenComments = hidden.reduce((sum, i) => sum + documents[i].commentCount, 0)

  function menuItem(i: number) {
    const doc = documents[i]
    const active = doc.id === activeId
    return (
      <DropdownMenuItem
        key={doc.id}
        onSelect={() => onSelect(doc.id)}
        aria-current={active ? 'true' : undefined}
        aria-label={`${doc.filename}${commentSuffix(doc.commentCount)}`}
        className={cn('gap-2', active && 'font-medium text-primary')}
      >
        <FileText className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        <span className="flex-1 truncate">{doc.filename}</span>
        {doc.external && <ExternalLink className="h-3 w-3 shrink-0 opacity-60" aria-hidden="true" />}
        {tabCommentCount(doc.commentCount, active)}
      </DropdownMenuItem>
    )
  }

  return (
    <div ref={stripRef} className="flex flex-1 min-w-0 items-center gap-1 overflow-x-clip">
      {shown.map((i) => {
        const doc = documents[i]
        const active = doc.id === activeId
        const label = `${doc.filename}${commentSuffix(doc.commentCount)}`
        return (
          <button
            key={doc.id}
            id={tabId(doc.id)}
            ref={(el) => {
              if (el) tabRefs.current.set(doc.id, el)
              else tabRefs.current.delete(doc.id)
            }}
            role="tab"
            data-tab-id={doc.id}
            aria-selected={active}
            aria-controls={panelId}
            tabIndex={active ? 0 : -1}
            onClick={() => onSelect(doc.id)}
            // Measured at full width; after that min-w-0 lets the one tab left on a phone truncate its
            // name rather than push "+N more" out of the strip.
            className={cn(TAB_CLASSES, measuring ? 'shrink-0' : 'min-w-0 overflow-hidden', tabStateClasses(active))}
            title={label}
            aria-label={label}
          >
            {/* On a phone the tab keeps only its name; the icon and count are in the menu and the label. */}
            <FileText className="hidden h-3.5 w-3.5 shrink-0 sm:block" />
            <span className="truncate max-w-[10rem]">{doc.filename}</span>
            {doc.external && <ExternalLink className="h-3 w-3 shrink-0 opacity-60" />}
            <span className="hidden sm:inline-flex">{tabCommentCount(doc.commentCount, active)}</span>
          </button>
        )
      })}
      {measuring && (
        // Stand-in for the widest "+N more" tab, measured with the others and never painted.
        <span ref={moreRef} aria-hidden="true" className={cn(TAB_CLASSES, 'invisible border-transparent')}>
          <MoreLabel count={documents.length} comments={documents.reduce((sum, d) => sum + d.commentCount, 0)} />
        </span>
      )}
      {hidden.length > 0 && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <button
              role="tab"
              aria-selected={false}
              tabIndex={-1}
              aria-label={`${hidden.length} more document${hidden.length !== 1 ? 's' : ''}${commentSuffix(hiddenComments)}`}
              className={cn(TAB_CLASSES, 'shrink-0 border-transparent text-foreground hover:text-primary')}
            >
              <MoreLabel count={hidden.length} comments={hiddenComments} />
            </button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" className="w-72 max-h-[60vh] overflow-y-auto">
            <DropdownMenuLabel className="text-xs font-medium text-muted-foreground">More documents</DropdownMenuLabel>
            {hidden.map(menuItem)}
            <DropdownMenuSeparator />
            <DropdownMenuLabel className="text-xs font-medium text-muted-foreground">In the tab bar</DropdownMenuLabel>
            {shown.map(menuItem)}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </div>
  )
}
