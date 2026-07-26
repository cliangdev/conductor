'use client'

import { useState } from 'react'
import { ChevronDownIcon, ChevronRightIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { readPersistedFlag, writePersisted, removePersisted } from '@/lib/persisted'
import type { KnowledgeTreeSection } from '@/lib/knowledgeTree'
import { KnowledgeTypeIcon } from '@/components/knowledge/KnowledgeTypeIcon'

function collapseKey(projectId: string, sectionId: string): string {
  return `kt_collapsed_${projectId}_${sectionId}`
}

function TreeSection({
  projectId,
  section,
  activePath,
  onNavigate,
  depth = 0,
}: {
  projectId: string
  section: KnowledgeTreeSection
  activePath: string
  onNavigate: (path: string) => void
  depth?: number
}) {
  const key = collapseKey(projectId, section.id)
  const [collapsed, setCollapsed] = useState(() => readPersistedFlag(key))
  const indent = depth * 12

  function toggle() {
    const next = !collapsed
    setCollapsed(next)
    if (next) writePersisted(key, '1')
    else removePersisted(key)
  }

  return (
    <li>
      <button
        onClick={toggle}
        style={{ paddingLeft: 8 + indent }}
        className="w-full flex items-center gap-1 pr-2 py-1.5 rounded-md text-xs font-semibold uppercase tracking-wider text-muted-foreground hover:bg-sidebar-hover transition-colors"
        aria-expanded={!collapsed}
      >
        {collapsed ? (
          <ChevronRightIcon className="h-3 w-3 shrink-0" />
        ) : (
          <ChevronDownIcon className="h-3 w-3 shrink-0" />
        )}
        <span className="truncate">{section.label}</span>
      </button>
      {!collapsed && (
        <ul className="space-y-0.5">
          {section.pages.map((page) => {
            const active = page.path === activePath
            return (
              <li key={page.path}>
                <button
                  onClick={() => onNavigate(page.path)}
                  title={page.title}
                  aria-current={active ? 'page' : undefined}
                  style={{ paddingLeft: 24 + indent }}
                  className={cn(
                    'w-full flex items-center gap-2 pr-2 py-1.5 rounded-md text-sm text-left transition-colors',
                    active
                      ? 'bg-sidebar-active text-sidebar-active-text font-medium'
                      : 'text-foreground hover:bg-sidebar-hover'
                  )}
                >
                  <KnowledgeTypeIcon type={page.type} className="h-3.5 w-3.5 shrink-0 opacity-70" />
                  <span className="truncate">{page.title}</span>
                </button>
              </li>
            )
          })}
          {section.children.map((child) => (
            <TreeSection
              key={child.id}
              projectId={projectId}
              section={child}
              activePath={activePath}
              onNavigate={onNavigate}
              depth={depth + 1}
            />
          ))}
        </ul>
      )}
    </li>
  )
}

/** Notion-style collapsible page tree for the knowledge rail — one disclosure section per top-level
 *  path segment (flat pages land in one "Pages" section). Collapse state persists per project+section. */
export function KnowledgePageTree({
  projectId,
  sections,
  activePath,
  onNavigate,
}: {
  projectId: string
  sections: KnowledgeTreeSection[]
  activePath: string
  onNavigate: (path: string) => void
}) {
  if (sections.length === 0) return null
  return (
    <nav aria-label="Knowledge pages" className="px-1 py-1">
      <ul className="space-y-0.5">
        {sections.map((section) => (
          <TreeSection
            key={section.id}
            projectId={projectId}
            section={section}
            activePath={activePath}
            onNavigate={onNavigate}
          />
        ))}
      </ul>
    </nav>
  )
}
