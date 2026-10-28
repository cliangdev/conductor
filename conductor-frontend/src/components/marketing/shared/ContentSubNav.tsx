'use client'

// Posts · Creatives · Media: the one sub-nav shared by the three content-library pages, so they read as
// one place. The sidebar keeps its separate entries; this only ties the three pages together.

import { usePathname } from 'next/navigation'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { useAuth } from '@/contexts/AuthContext'
import { findPostsNavEntry, useSidebarWorkNav, workItemListPath } from '@/lib/workflows'

export type ContentSection = 'posts' | 'creatives' | 'media'

/** The Posts list URL for the project's publishing workflow, or null until the nav has loaded / none exists. */
export function usePostsHref(projectId: string): string | null {
  const { accessToken } = useAuth()
  const { entries } = useSidebarWorkNav(projectId, accessToken)
  const posts = findPostsNavEntry(entries)
  return posts ? workItemListPath(projectId, posts.area, posts.noun) : null
}

function SubNavTabs({
  projectId,
  active,
  posts,
}: {
  projectId: string
  active?: ContentSection
  posts: string | null
}) {
  const pathname = usePathname()
  const current: ContentSection | undefined =
    active ?? (pathname?.includes('/marketing/creatives') ? 'creatives' : pathname?.includes('/marketing/media') ? 'media' : undefined)

  const items: TabItem[] = [
    ...(posts ? [{ value: 'posts', label: 'Posts', href: posts }] : []),
    { value: 'creatives', label: 'Creatives', href: `/app/projects/${projectId}/marketing/creatives` },
    { value: 'media', label: 'Media', href: `/app/projects/${projectId}/marketing/media` },
  ]
  return <Tabs items={items} value={current ?? ''} ariaLabel="Content library" className="mb-4" />
}

/** The Posts page knows its own list path, so it needs no lookup. */
function KnownPostsSubNav({ projectId, active, postsHref }: { projectId: string; active?: ContentSection; postsHref: string }) {
  return <SubNavTabs projectId={projectId} active={active} posts={postsHref} />
}

/** The Creatives and Media pages look the Posts list up from the workflow nav. */
function LookedUpSubNav({ projectId, active }: { projectId: string; active?: ContentSection }) {
  const looked = usePostsHref(projectId)
  return <SubNavTabs projectId={projectId} active={active} posts={looked} />
}

export function ContentSubNav({
  projectId,
  active,
  postsHref,
}: {
  projectId: string
  active?: ContentSection
  /** Known by the Posts page itself; the other pages leave it out and it is looked up. */
  postsHref?: string
}) {
  // Two components rather than one conditional hook call: the lookup (an auth read and a nav subscription)
  // only runs where it is needed.
  return postsHref ? (
    <KnownPostsSubNav projectId={projectId} active={active} postsHref={postsHref} />
  ) : (
    <LookedUpSubNav projectId={projectId} active={active} />
  )
}
