'use client'

// The static (non-workflow-derived) workspace destinations, shared by the Sidebar and the
// CommandPalette so there is exactly one place that lists "the other things in a workspace" —
// adding/renaming/removing a destination only requires touching this file.

import { usePathname } from 'next/navigation'
import {
  BellIcon,
  BookOpenIcon,
  BotIcon,
  BrainIcon,
  GitBranchIcon,
  KeyIcon,
  LibraryIcon,
  LockIcon,
  PuzzleIcon,
  SlidersHorizontalIcon,
  SparklesIcon,
  TerminalIcon,
  UsersIcon,
  type LucideIcon,
} from 'lucide-react'
import { useProject } from '@/contexts/ProjectContext'
import type { Capability } from '@/lib/permissions'
import type { Project } from '@/types'

export interface StaticNavEntry {
  key: string
  label: string
  icon: LucideIcon
  path: (workspaceId: string) => string
  /**
   * Capability required to see this entry in nav (sidebar rail, Settings door, command palette).
   * Omit for entries every role should see. Per the audience-layers IA rule (docs/design-system.md),
   * a role that can't use an entry doesn't see it at all — never a disabled/greyed-out row.
   */
  permission?: Capability
}

/** Filter nav entries down to the ones the current role can see — the one gating rule, reused by every nav surface. */
export function visibleNavEntries(entries: StaticNavEntry[], can: (capability: Capability) => boolean): StaticNavEntry[] {
  return entries.filter((entry) => !entry.permission || can(entry.permission))
}

export const WORKSPACE_NAV: StaticNavEntry[] = [
  { key: 'docs', label: 'Docs', icon: BookOpenIcon, path: (id) => `/app/projects/${id}/docs` },
  { key: 'knowledge', label: 'Knowledge', icon: LibraryIcon, path: (id) => `/app/projects/${id}/knowledge` },
  { key: 'memory', label: 'Memory', icon: BrainIcon, path: (id) => `/app/projects/${id}/memory` },
]

export const AUTOMATION_NAV: StaticNavEntry[] = [
  { key: 'workflows', label: 'Workflows', icon: GitBranchIcon, path: (id) => `/app/projects/${id}/workflows` },
  { key: 'agents', label: 'Agents', icon: BotIcon, path: (id) => `/app/projects/${id}/agents` },
  { key: 'integrations', label: 'Integrations', icon: PuzzleIcon, path: (id) => `/app/projects/${id}/integrations` },
]

// Members & Roles and API Keys are deliberately ungated despite the "admin-flavored" label they're
// sometimes given: Members' roster is read-only-usable by every role (only invite/remove/role-change
// are capability-gated inside the page), and API Keys has no capability check at all — the keys are
// personal, tied to the signed-in user, not the workspace. AI Providers and Secrets *are* gated:
// both are credential/provisioning surfaces (the Configure layer's own definition) whose read-only
// view (connection badges, secret key names) isn't the reason anyone would visit.
export const SETTINGS_NAV: StaticNavEntry[] = [
  { key: 'settings-general', label: 'General', icon: SlidersHorizontalIcon, path: (id) => `/app/projects/${id}/settings/general` },
  { key: 'settings-members', label: 'Members & Roles', icon: UsersIcon, path: (id) => `/app/projects/${id}/settings/members` },
  { key: 'settings-api-keys', label: 'API Keys', icon: KeyIcon, path: (id) => `/app/projects/${id}/settings/api-keys` },
  { key: 'settings-providers', label: 'AI Providers', icon: SparklesIcon, path: (id) => `/app/projects/${id}/settings/providers`, permission: 'agent.manage' },
  { key: 'settings-secrets', label: 'Secrets', icon: LockIcon, path: (id) => `/app/projects/${id}/settings/secrets`, permission: 'workflow.manage' },
  { key: 'settings-notifications', label: 'Notifications', icon: BellIcon, path: (id) => `/app/projects/${id}/settings/notifications` },
  { key: 'settings-cli', label: 'CLI', icon: TerminalIcon, path: (id) => `/app/projects/${id}/settings/cli` },
]

/** Where switching to a workspace lands — mirrors the sidebar's workspace switcher. */
export function workspaceHomePath(workspaceId: string): string {
  return `/app/projects/${workspaceId}/engineering/issues`
}

/**
 * The `Settings › {section}` breadcrumb trail for a settings leaf page, derived from SETTINGS_NAV
 * so the section label exists in exactly one place (the settings sub-nav and every leaf page's
 * PageHeader agree by construction).
 */
export function settingsBreadcrumbs(projectId: string, key: string): { label: string; href?: string }[] {
  const entry = SETTINGS_NAV.find((s) => s.key === key)
  return [
    { label: 'Settings', href: SETTINGS_NAV[0].path(projectId) },
    { label: entry?.label ?? 'Settings' },
  ]
}

/**
 * Resolve which workspace the current route is in: the project matching the `/app/projects/{id}`
 * URL segment if present, falling back to whatever ProjectContext considers active (routes with
 * no project segment, e.g. the workspace picker). Shared by the Sidebar, Navbar, and
 * CommandPalette so there is exactly one implementation of "what workspace am I looking at".
 */
export function useCurrentWorkspace(): Project | null {
  const pathname = usePathname()
  const { projects, activeProject } = useProject()
  const projectIdFromPath = pathname.match(/\/app\/projects\/([^/]+)/)?.[1]
  return projects.find((p) => p.id === projectIdFromPath) ?? activeProject
}
