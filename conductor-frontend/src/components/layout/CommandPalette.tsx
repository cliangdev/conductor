'use client'

// The single ⌘K / Ctrl+K command palette, mounted once in the app layout. Extensible via
// registerPaletteActions() below — later phases (e.g. a Work Item detail's "change status" /
// "assign" actions) register a group from an effect without touching the navigation/workspace/
// theme groups built here.

import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react'
import { usePathname, useRouter } from 'next/navigation'
import { Dialog } from '@base-ui/react/dialog'
import { useTheme } from 'next-themes'
import { CheckIcon, FileTextIcon, type LucideIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { useProject } from '@/contexts/ProjectContext'
import { useSidebar } from '@/contexts/SidebarContext'
import { useSidebarWorkNav, workItemListPath } from '@/lib/workflows'
import { AUTOMATION_NAV, SETTINGS_NAV, WORKSPACE_NAV, useCurrentWorkspace, workspaceHomePath } from '@/lib/navigation'
import { THEME_OPTIONS } from '@/lib/theme'
import { ShortcutKbd } from '@/components/ui/shortcut-kbd'
import { cn } from '@/lib/utils'
import type { Project } from '@/types'

/** One command — the shape later phases extend with context actions (e.g. "change status"). */
export interface PaletteAction {
  id: string
  label: string
  icon?: LucideIcon
  keywords?: string[]
  shortcut?: string
  /** Marks the currently-active choice within a group (e.g. the active theme) — renders a check. */
  current?: boolean
  perform: () => void
}

export interface PaletteActionGroup {
  group: string
  actions: PaletteAction[]
}

// ─── Extensibility registry ─────────────────────────────────────────────────
// A plain module-scope subscribe/notify store rather than context — the palette mounts once at
// the app shell, far above wherever a registrant (e.g. a Work Item detail page) lives.

type Listener = () => void

const registry = new Map<symbol, PaletteActionGroup>()
const listeners = new Set<Listener>()

// useSyncExternalStore requires getSnapshot to return a referentially stable value when nothing
// changed (otherwise React re-renders forever) — so the flattened array is cached here and only
// rebuilt when the registry actually mutates, not on every snapshot() call.
let cachedSnapshot: PaletteActionGroup[] = []

function notify() {
  cachedSnapshot = [...registry.values()]
  listeners.forEach((l) => l())
}

/** Register a group of context actions with the command palette. Call the returned function to unregister (e.g. from a `useEffect` cleanup). */
export function registerPaletteActions(group: PaletteActionGroup): () => void {
  const token = Symbol(group.group)
  registry.set(token, group)
  notify()
  return () => {
    registry.delete(token)
    notify()
  }
}

function subscribe(listener: Listener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function snapshot(): PaletteActionGroup[] {
  return cachedSnapshot
}

function matches(action: PaletteAction, query: string): boolean {
  if (!query) return true
  const q = query.toLowerCase()
  if (action.label.toLowerCase().includes(q)) return true
  return action.keywords?.some((k) => k.toLowerCase().includes(q)) ?? false
}

export function CommandPalette() {
  const { paletteOpen, setPaletteOpen, closeSidebar } = useSidebar()
  const router = useRouter()
  const pathname = usePathname()
  const { accessToken } = useAuth()
  const { projects, setActiveProject } = useProject()
  const currentWorkspace = useCurrentWorkspace()
  const { theme, setTheme } = useTheme()
  const registeredGroups = useSyncExternalStore(subscribe, snapshot, snapshot)

  // Scoped strictly to the URL's project segment (not the activeProject fallback) so the palette
  // doesn't fetch a workflow list while on project-less routes like the workspace picker.
  const projectIdFromPath = pathname.match(/\/app\/projects\/([^/]+)/)?.[1]
  const { entries: workNav } = useSidebarWorkNav(projectIdFromPath, accessToken)

  const [query, setQuery] = useState('')
  const [activeIndex, setActiveIndex] = useState(0)
  const inputRef = useRef<HTMLInputElement>(null)

  // Global ⌘K / Ctrl+K toggle — mounted once here, works regardless of focus. Bails when another
  // handler already claimed the shortcut (e.g. DocEditor calls preventDefault on mod+K to insert a
  // link) so the two don't fight over the same keystroke.
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.defaultPrevented) return
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPaletteOpen(!paletteOpen)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [paletteOpen, setPaletteOpen])

  // Reset transient state whenever the palette opens. Dialog.Popup's initialFocus handles focus.
  useEffect(() => {
    if (paletteOpen) {
      setQuery('')
      setActiveIndex(0)
    }
  }, [paletteOpen])

  function switchWorkspace(workspace: Project) {
    setActiveProject(workspace)
    router.push(workspaceHomePath(workspace.id))
  }

  const groups = useMemo<PaletteActionGroup[]>(() => {
    const result: PaletteActionGroup[] = []

    if (currentWorkspace) {
      const workspaceId = currentWorkspace.id
      const navActions: PaletteAction[] = [
        ...workNav.map((entry) => ({
          id: `work-${entry.slug}`,
          label: entry.label,
          icon: FileTextIcon,
          keywords: [entry.area, entry.noun],
          perform: () => router.push(workItemListPath(workspaceId, entry.area, entry.noun)),
        })),
        ...[...WORKSPACE_NAV, ...AUTOMATION_NAV, ...SETTINGS_NAV].map((entry) => ({
          id: `nav-${entry.key}`,
          label: entry.label,
          icon: entry.icon,
          perform: () => router.push(entry.path(workspaceId)),
        })),
      ]
      result.push({ group: 'Navigation', actions: navActions })
    }

    const otherWorkspaces = projects.filter((p) => p.id !== currentWorkspace?.id)
    if (otherWorkspaces.length > 0) {
      result.push({
        group: 'Workspaces',
        actions: otherWorkspaces.map((workspace) => ({
          id: `workspace-${workspace.id}`,
          label: `Switch to ${workspace.name}`,
          keywords: [workspace.name],
          perform: () => switchWorkspace(workspace),
        })),
      })
    }

    result.push({
      group: 'Theme',
      actions: THEME_OPTIONS.map((opt) => ({
        id: `theme-${opt.value}`,
        label: opt.label,
        icon: opt.icon,
        current: theme === opt.value,
        perform: () => setTheme(opt.value),
      })),
    })

    return [...result, ...registeredGroups]
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentWorkspace, workNav, projects, theme, registeredGroups])

  const filteredGroups = useMemo(
    () =>
      groups
        .map((g) => ({ group: g.group, actions: g.actions.filter((a) => matches(a, query)) }))
        .filter((g) => g.actions.length > 0),
    [groups, query],
  )

  const flatActions = useMemo(() => filteredGroups.flatMap((g) => g.actions), [filteredGroups])

  // Keep the active index in range whenever the filtered list changes size.
  useEffect(() => {
    if (activeIndex >= flatActions.length) setActiveIndex(Math.max(0, flatActions.length - 1))
  }, [flatActions.length, activeIndex])

  const activeId = flatActions[activeIndex]?.id

  // Keep the active option in view as arrow keys move it through the max-h-80 scrollable list.
  useEffect(() => {
    if (!activeId) return
    const el = document.getElementById(activeId)
    if (typeof el?.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' })
  }, [activeId])

  function close() {
    setPaletteOpen(false)
  }

  function runAction(action: PaletteAction) {
    close()
    // No-op on desktop; on mobile this dismisses the drawer so it doesn't cover the destination —
    // the same mechanism NavItems use for in-sidebar navigation.
    closeSidebar()
    action.perform()
  }

  function onInputKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActiveIndex((i) => (flatActions.length === 0 ? 0 : (i + 1) % flatActions.length))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveIndex((i) => (flatActions.length === 0 ? 0 : (i - 1 + flatActions.length) % flatActions.length))
    } else if (e.key === 'Enter') {
      // Let an in-progress IME composition (e.g. picking a Japanese candidate) commit instead of
      // treating its confirming Enter as "run the active action".
      if (e.nativeEvent.isComposing || e.keyCode === 229) return
      e.preventDefault()
      const action = flatActions[activeIndex]
      if (action) runAction(action)
    }
    // Escape and Tab are handled by Dialog.Popup itself (dialog-level Escape-to-close, built-in
    // focus trap) — no manual handling needed here.
  }

  // Options are virtual (selection tracked via aria-activedescendant, not real DOM focus), so a
  // mousedown anywhere in the list — including a scrollbar drag — must never steal focus from the
  // input, or arrow keys/Enter would stop working mid-interaction.
  function preserveInputFocus(e: React.MouseEvent) {
    e.preventDefault()
  }

  return (
    <Dialog.Root open={paletteOpen} onOpenChange={setPaletteOpen}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 bg-black/40 dark:bg-black/60 z-40" />
        <Dialog.Popup
          initialFocus={inputRef}
          aria-label="Command palette"
          className={cn(
            'fixed left-1/2 top-[12vh] z-50 -translate-x-1/2',
            'w-full max-w-lg mx-4 rounded-lg border border-border bg-popover shadow-lg overflow-hidden',
          )}
        >
          <div className="flex items-center border-b border-border px-3">
            <input
              ref={inputRef}
              role="combobox"
              aria-expanded="true"
              aria-controls="command-palette-listbox"
              aria-activedescendant={activeId}
              value={query}
              onChange={(e) => {
                setQuery(e.target.value)
                setActiveIndex(0)
              }}
              onKeyDown={onInputKeyDown}
              placeholder="Type a command or search…"
              className="w-full bg-transparent py-3 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
            />
          </div>

          <div
            id="command-palette-listbox"
            role="listbox"
            className="max-h-80 overflow-y-auto py-2"
            onMouseDown={preserveInputFocus}
          >
            {flatActions.length === 0 ? (
              <p className="px-4 py-6 text-center text-sm text-muted-foreground">No matching commands.</p>
            ) : (
              filteredGroups.map((g) => (
                <div key={g.group} className="mb-1 last:mb-0">
                  <div className="px-3 pb-1 pt-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                    {g.group}
                  </div>
                  {g.actions.map((action) => {
                    const isActive = action.id === activeId
                    const Icon = action.icon
                    return (
                      <div
                        key={action.id}
                        id={action.id}
                        role="option"
                        aria-selected={isActive}
                        onMouseEnter={() => setActiveIndex(flatActions.indexOf(action))}
                        onClick={() => runAction(action)}
                        className={cn(
                          'flex items-center gap-2.5 mx-2 px-2.5 py-2 rounded-md text-sm cursor-pointer',
                          isActive ? 'bg-accent-soft text-foreground' : 'text-foreground hover:bg-muted',
                        )}
                      >
                        {Icon && <Icon className="h-4 w-4 shrink-0 opacity-70" />}
                        <span className="flex-1 truncate">{action.label}</span>
                        {action.current && <CheckIcon className="h-3.5 w-3.5 shrink-0" />}
                        {action.shortcut && <ShortcutKbd>{action.shortcut}</ShortcutKbd>}
                      </div>
                    )
                  })}
                </div>
              ))
            )}
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
