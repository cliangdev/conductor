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
  const pathname = usePathname()
  const looked = usePostsHref(postsHref ? '' : projectId)
  const posts = postsHref ?? looked
  const current: ContentSection | undefined =
    active ?? (pathname?.includes('/marketing/creatives') ? 'creatives' : pathname?.includes('/marketing/media') ? 'media' : undefined)

  const items: TabItem[] = [
    ...(posts ? [{ value: 'posts', label: 'Posts', href: posts }] : []),
    { value: 'creatives', label: 'Creatives', href: `/app/projects/${projectId}/marketing/creatives` },
    { value: 'media', label: 'Media', href: `/app/projects/${projectId}/marketing/media` },
  ]
  return <Tabs items={items} value={current ?? ''} ariaLabel="Content library" className="mb-4" />
}
