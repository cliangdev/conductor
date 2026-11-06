'use client'

// What a creator has produced: its posts with their views, the downloads of its campaign links and the
// redemptions of its offer code. Shown on the Work Item detail page of a CREATORS item.
//
// A figure the sources did not report is a dash with the reason beside it, never a 0: Apple omits campaign
// rows under its reporting threshold, and a product backend may not send redemptions at all. Only a number
// the backend returns (a reported 0 included) is printed as one.

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { apiErrorMessage, apiGet } from '@/lib/api'
import { humanizeId } from '@/lib/workflows'
import { count } from '@/components/marketing/destinations/DestinationMetrics'
import { usePostsHref } from '@/components/marketing/shared/usePostsHref'
import { formatDay } from '@/components/workitems/WorkItemFieldsCard'

export interface CreatorResultsPost {
  workItemId: string
  displayId: string
  title: string
  status: string
  views?: number | null
}

export interface CreatorResults {
  window: { from: string; to: string }
  creator: { workItemId: string; displayId: string; handle?: string | null; status: string }
  postCount: number
  posts: CreatorResultsPost[]
  linkDownloads: { tokens: string[]; downloads?: number | null; belowThreshold: boolean; note?: string | null }
  offerRedemptions: { code?: string | null; value?: number | null; note?: string | null }
}

export interface CreatorResultsCardProps {
  projectId: string
  workItemId: string
  token: string
  /** The creator's status and fields: a move or a changed handle, code or link token re-reads the results. */
  status: string
  fields?: Record<string, unknown> | null
}

function fieldText(fields: Record<string, unknown> | null | undefined, key: string): string {
  const value = fields?.[key]
  return typeof value === 'string' ? value : ''
}

/** A figure, or a dash that says "not reported" when the backend sent none. */
function Figure({ value }: { value: number | null | undefined }) {
  if (value === null || value === undefined) {
    return (
      <span className="text-muted-foreground" title="Not reported">
        —
      </span>
    )
  }
  return <span className="tabular-nums text-foreground">{count(value)}</span>
}

function Section({ label, testId, children }: { label: string; testId: string; children: React.ReactNode }) {
  return (
    <section data-testid={testId} className="px-4 py-3">
      <h3 className="mb-2 text-[11.5px] font-semibold uppercase tracking-wide text-muted-foreground">{label}</h3>
      {children}
    </section>
  )
}

export function CreatorResultsCard({ projectId, workItemId, token, status, fields }: CreatorResultsCardProps) {
  const postsHref = usePostsHref(projectId)
  const [data, setData] = useState<CreatorResults | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Primitives, not the fields object: a re-render with the same values must not read again.
  const handle = fieldText(fields, 'handle')
  const offerCode = fieldText(fields, 'offerCode')
  const linkToken = fieldText(fields, 'linkToken')

  useEffect(() => {
    let current = true
    apiGet<CreatorResults>(`/api/v2/projects/${projectId}/marketing/creators/${workItemId}/results`, token)
      .then((results) => {
        if (!current) return
        setData(results)
        setError(null)
      })
      .catch((err) => {
        if (current) setError(apiErrorMessage(err, 'Could not read the creator results'))
      })
    return () => {
      current = false
    }
  }, [projectId, workItemId, token, status, handle, offerCode, linkToken])

  return (
    <Card data-testid="creator-results">
      <CardHeader>
        <h2 className="text-sm font-medium text-foreground">Results</h2>
        {data && (
          <span className="text-xs text-muted-foreground">
            {formatDay(data.window.from)} – {formatDay(data.window.to)}
          </span>
        )}
      </CardHeader>
      <CardContent>
        {error ? (
          <p className="px-4 py-3 text-sm text-muted-foreground">{error}</p>
        ) : !data ? (
          <div className="space-y-2 px-4 py-3" aria-busy="true">
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-4 w-2/3" />
            <Skeleton className="h-4 w-1/3" />
          </div>
        ) : (
          <>
            <Section label="Posts" testId="creator-posts">
              {data.posts.length === 0 ? (
                <p className="text-sm text-muted-foreground">
                  No posts yet. A Post counts here when it is a child of this creator
                  {data.creator.handle ? (
                    <>
                      {' '}
                      or carries the tag{' '}
                      <span className="font-mono text-xs">creator:{data.creator.handle.replace(/^@+/, '').toLowerCase()}</span>
                    </>
                  ) : null}
                  .
                </p>
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="text-left text-xs text-muted-foreground">
                        <th className="py-1 pr-3 font-medium">Post</th>
                        <th className="py-1 pr-3 font-medium">Status</th>
                        <th className="py-1 text-right font-medium">Views</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.posts.map((post) => (
                        <tr key={post.workItemId} className="border-t border-border">
                          <td className="py-1.5 pr-3">
                            <span className="mr-2 font-mono text-xs text-muted-foreground">{post.displayId}</span>
                            {postsHref ? (
                              <Link prefetch={false} href={`${postsHref}/${post.displayId}`} className="text-foreground hover:underline">
                                {post.title}
                              </Link>
                            ) : (
                              <span className="text-foreground">{post.title}</span>
                            )}
                          </td>
                          <td className="py-1.5 pr-3 text-muted-foreground">{humanizeId(post.status)}</td>
                          <td className="py-1.5 text-right">
                            <Figure value={post.views} />
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {data.postCount > data.posts.length && (
                    <p className="mt-2 text-xs text-muted-foreground">
                      Showing {data.posts.length} of {data.postCount} posts, newest first.
                    </p>
                  )}
                </div>
              )}
            </Section>

            <Section label="Link downloads" testId="creator-downloads">
              <p className="text-lg font-semibold">
                <Figure value={data.linkDownloads.downloads} />
              </p>
              {data.linkDownloads.note && <p className="mt-1 text-sm text-muted-foreground">{data.linkDownloads.note}</p>}
              {data.linkDownloads.tokens.length > 0 && (
                <ul className="mt-2 flex flex-wrap gap-1.5" aria-label="Campaign tokens">
                  {data.linkDownloads.tokens.map((t) => (
                    <li key={t} className="rounded-full border border-border px-2 py-0.5 font-mono text-xs text-muted-foreground">
                      {t}
                    </li>
                  ))}
                </ul>
              )}
            </Section>

            <Section label="Offer code redemptions" testId="creator-redemptions">
              <p className="text-lg font-semibold">
                <Figure value={data.offerRedemptions.value} />
                {data.offerRedemptions.code && (
                  <span className="ml-2 align-middle font-mono text-xs font-normal text-muted-foreground">
                    {data.offerRedemptions.code}
                  </span>
                )}
              </p>
              {data.offerRedemptions.note && <p className="mt-1 text-sm text-muted-foreground">{data.offerRedemptions.note}</p>}
            </Section>
          </>
        )}
      </CardContent>
    </Card>
  )
}
