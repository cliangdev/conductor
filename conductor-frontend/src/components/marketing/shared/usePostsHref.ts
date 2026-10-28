'use client'

import { useAuth } from '@/contexts/AuthContext'
import { findPostsNavEntry, useSidebarWorkNav, workItemListPath } from '@/lib/workflows'

/** The Posts list URL for the project's publishing workflow, or null until the nav has loaded / none exists. */
export function usePostsHref(projectId: string): string | null {
  const { accessToken } = useAuth()
  const { entries } = useSidebarWorkNav(projectId, accessToken)
  const posts = findPostsNavEntry(entries)
  return posts ? workItemListPath(projectId, posts.area, posts.noun) : null
}
