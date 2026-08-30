'use client'

// COND-23 T7.2: the Area asset library — every uploaded image/video produced under one Area, laid out
// as a thumbnail grid.
//
// The query keys on the Area, never on a Workflow slug (see openapi-v2 `listAreaAssets`), so a second
// Workflow added to Marketing later shows up here with no change. The sidebar workflow list is read
// alongside it for two reasons: it supplies the "owning workflow" filter options, and it is the only
// place the *noun* behind a workflow slug lives — which the detail route needs, since a row carries
// its owning Workflow's slug but the URL is built from area + pluralized noun.
//
// Every `previewUrl` is a 15-minute signed URL minted per response: never cache one, and always
// re-render from the freshly fetched rows.

import Link from 'next/link'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { FilmIcon, ImageIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { isVideoContentType } from '@/components/workitems/MediaUploadPanel'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet } from '@/lib/api'
import { timeAgo } from '@/lib/format'
import { statusMeta, useSidebarWorkNav, useWorkflowViews, workItemDetailPath } from '@/lib/workflows'
import type { WorkNavEntry } from '@/lib/workflows'

/** The Work Item that produced a library Asset — enough to label and link the tile. */
export interface AreaAssetWorkItemRef {
  id: string
  displayId: string
  title: string
  status: string
  workflow: string
}

/** One uploaded file Asset in an Area's library (v2 `AreaAssetResponse`). */
export interface AreaAsset {
  assetId: string
  /** Short-lived signed read URL, minted per response — never persist or cache it. */
  previewUrl: string
  contentType?: string | null
  sizeBytes?: number | null
  uploadedAt: string
  workItem: AreaAssetWorkItemRef
}

export type AssetMediaType = 'image' | 'video'

export interface AreaAssetQuery {
  mediaType?: AssetMediaType | ''
  workflow?: string
  status?: string
  uploadedAfter?: string
  uploadedBefore?: string
  page?: number
  size?: number
}

export const ASSET_PAGE_SIZE = 24

/** `GET /api/v2/projects/{id}/areas/{area}/assets` — a bare array, page/size params (the repo's one
 *  pagination convention). A full page implies there may be another. */
export function listAreaAssets(
  projectId: string,
  area: string,
  token: string,
  query: AreaAssetQuery = {},
): Promise<AreaAsset[]> {
  const params = new URLSearchParams({
    page: String(query.page ?? 0),
    size: String(query.size ?? ASSET_PAGE_SIZE),
  })
  if (query.mediaType) params.set('mediaType', query.mediaType)
  if (query.workflow) params.set('workflow', query.workflow)
  if (query.status) params.set('status', query.status)
  if (query.uploadedAfter) params.set('uploadedAfter', query.uploadedAfter)
  if (query.uploadedBefore) params.set('uploadedBefore', query.uploadedBefore)
  return apiGet<AreaAsset[]>(
    `/api/v2/projects/${projectId}/areas/${area.toLowerCase()}/assets?${params.toString()}`,
    token,
  )
}

// Upload-date filter. Presets rather than two date pickers: "what landed recently" is the question a
// library gets asked, and one select keeps the filter row a single dense line. The endpoint's
// uploadedBefore bound stays available to callers of listAreaAssets.
const UPLOADED_WINDOWS = [
  { value: '', label: 'Any time' },
  { value: '7', label: 'Last 7 days' },
  { value: '30', label: 'Last 30 days' },
  { value: '90', label: 'Last 90 days' },
] as const

function windowStart(days: string): string | undefined {
  if (!days) return undefined
  return new Date(Date.now() - Number(days) * 24 * 60 * 60 * 1000).toISOString()
}

interface Filters {
  mediaType: AssetMediaType | ''
  workflow: string
  status: string
  uploadedWithinDays: string
}

const NO_FILTERS: Filters = { mediaType: '', workflow: '', status: '', uploadedWithinDays: '' }

function FilterField({
  id,
  label,
  children,
}: {
  id: string
  label: string
  children: React.ReactNode
}) {
  return (
    <div className="min-w-[9rem]">
      <Label htmlFor={id} className="text-xs font-medium text-muted-foreground">
        {label}
      </Label>
      {children}
    </div>
  )
}

function AssetTile({
  asset,
  href,
  statusLabel,
  statusCategory,
}: {
  asset: AreaAsset
  href: string
  statusLabel: string
  statusCategory: string
}) {
  const isVideo = isVideoContentType(asset.contentType)
  const { title, displayId, status } = asset.workItem

  return (
    <Link
      href={href}
      className="group block overflow-hidden rounded-lg border border-border bg-surface transition-colors hover:border-border-strong"
    >
      <div className="relative aspect-square bg-surface-3">
        {isVideo ? (
          <>
            {/* preload="metadata" is what paints the poster frame — no controls, this is a thumbnail. */}
            <video
              src={asset.previewUrl}
              preload="metadata"
              muted
              playsInline
              className="h-full w-full object-cover"
            />
            <span className="absolute left-2 top-2 inline-flex items-center gap-1 rounded-full bg-background/90 px-2 py-0.5 text-xs font-medium text-foreground">
              <FilmIcon className="h-3 w-3" aria-hidden />
              Video
            </span>
          </>
        ) : (
          // eslint-disable-next-line @next/next/no-img-element
          <img
            src={asset.previewUrl}
            alt={title}
            loading="lazy"
            className="h-full w-full object-cover"
          />
        )}
      </div>

      <div className="flex flex-col gap-1.5 border-t border-border p-3">
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <span className="font-mono">{displayId}</span>
          <span className="ml-auto shrink-0">{timeAgo(asset.uploadedAt)}</span>
        </div>
        <p className="truncate text-sm text-foreground" title={title}>
          {title}
        </p>
        <StatusBadge status={status} category={statusCategory} label={statusLabel} className="self-start" />
      </div>
    </Link>
  )
}

function GridSkeleton() {
  return (
    <div
      data-testid="asset-grid-skeleton"
      className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4"
      aria-hidden
    >
      {Array.from({ length: 8 }, (_, i) => (
        <div key={i} className="overflow-hidden rounded-lg border border-border">
          <Skeleton className="aspect-square rounded-none" />
          <div className="space-y-2 p-3">
            <Skeleton className="h-3 w-16" />
            <Skeleton className="h-3.5 w-3/4" />
          </div>
        </div>
      ))}
    </div>
  )
}

export interface AssetLibraryGridProps {
  projectId: string
  /** Area slug, e.g. `MARKETING`. Matched case-insensitively server-side. */
  area: string
}

export function AssetLibraryGrid({ projectId, area }: AssetLibraryGridProps) {
  const { accessToken } = useAuth()
  const [filters, setFilters] = useState<Filters>(NO_FILTERS)
  const [page, setPage] = useState(0)
  const [assets, setAssets] = useState<AreaAsset[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  // Workflows in this Area: the "owning workflow" filter options, and the slug → noun map the
  // detail route is built from.
  const { entries } = useSidebarWorkNav(projectId, accessToken)
  const areaEntries = useMemo(
    () => entries.filter((e) => e.area.toLowerCase() === area.toLowerCase()),
    [entries, area],
  )
  const nounBySlug = useMemo(() => {
    const map: Record<string, WorkNavEntry> = {}
    for (const entry of areaEntries) map[entry.slug] = entry
    return map
  }, [areaEntries])

  // Status filter options come from the Area's Workflow statecharts — statuses are Workflow-defined
  // strings, so there is no enum to read them off.
  const views = useWorkflowViews(projectId, areaEntries.map((e) => e.slug), accessToken)
  const statusOptions = useMemo(() => {
    const seen = new Map<string, string>()
    for (const view of Object.values(views)) {
      for (const status of view.statuses) if (!seen.has(status.id)) seen.set(status.id, status.label)
    }
    return [...seen.entries()]
  }, [views])

  useEffect(() => {
    if (!projectId || !accessToken) return
    let cancelled = false
    listAreaAssets(projectId, area, accessToken, {
      mediaType: filters.mediaType,
      workflow: filters.workflow || undefined,
      status: filters.status || undefined,
      uploadedAfter: windowStart(filters.uploadedWithinDays),
      page,
      size: ASSET_PAGE_SIZE,
    })
      .then((rows) => {
        if (cancelled) return
        setAssets(rows)
        setError(null)
      })
      .catch(() => {
        if (cancelled) return
        setAssets([])
        setError('Could not load the asset library — please try again.')
      })
    return () => {
      cancelled = true
    }
  }, [projectId, area, accessToken, filters, page])

  // Every filter change invalidates the current offset — page 2 of the old query means nothing.
  const setFilter = useCallback(<K extends keyof Filters>(key: K, value: Filters[K]) => {
    setFilters((prev) => ({ ...prev, [key]: value }))
    setPage(0)
  }, [])

  const filtered = Object.values(filters).some(Boolean)
  const loading = assets === null
  const hasNextPage = (assets?.length ?? 0) === ASSET_PAGE_SIZE

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <FilterField id="asset-media-type" label="Media type">
          <Select
            id="asset-media-type"
            value={filters.mediaType}
            onChange={(e) => setFilter('mediaType', e.target.value as AssetMediaType | '')}
          >
            <option value="">All media</option>
            <option value="image">Images</option>
            <option value="video">Videos</option>
          </Select>
        </FilterField>

        <FilterField id="asset-workflow" label="Workflow">
          <Select
            id="asset-workflow"
            value={filters.workflow}
            onChange={(e) => setFilter('workflow', e.target.value)}
          >
            <option value="">All workflows</option>
            {areaEntries.map((entry) => (
              <option key={entry.slug} value={entry.slug}>
                {entry.label}
              </option>
            ))}
          </Select>
        </FilterField>

        <FilterField id="asset-status" label="Status">
          <Select
            id="asset-status"
            value={filters.status}
            onChange={(e) => setFilter('status', e.target.value)}
          >
            <option value="">All statuses</option>
            {statusOptions.map(([id, label]) => (
              <option key={id} value={id}>
                {label}
              </option>
            ))}
          </Select>
        </FilterField>

        <FilterField id="asset-uploaded" label="Uploaded">
          <Select
            id="asset-uploaded"
            value={filters.uploadedWithinDays}
            onChange={(e) => setFilter('uploadedWithinDays', e.target.value)}
          >
            {UPLOADED_WINDOWS.map((w) => (
              <option key={w.value} value={w.value}>
                {w.label}
              </option>
            ))}
          </Select>
        </FilterField>

        {filtered && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setFilters(NO_FILTERS)
              setPage(0)
            }}
          >
            Clear filters
          </Button>
        )}
      </div>

      {error && <Alert variant="destructive">{error}</Alert>}

      {loading ? (
        <GridSkeleton />
      ) : assets.length === 0 ? (
        !error && (
          <EmptyState
            icon={ImageIcon}
            title="No assets yet"
            description={
              filtered
                ? 'No uploaded images or videos match these filters.'
                : 'Images and videos uploaded to this area’s work items show up here.'
            }
          />
        )
      ) : (
        <>
          <div data-testid="asset-grid" className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
            {assets.map((asset) => {
              const entry = nounBySlug[asset.workItem.workflow]
              const meta = statusMeta(views[asset.workItem.workflow], asset.workItem.status)
              return (
                <AssetTile
                  key={asset.assetId}
                  asset={asset}
                  href={workItemDetailPath(
                    projectId,
                    entry?.area ?? area,
                    entry?.noun ?? asset.workItem.workflow,
                    asset.workItem.displayId,
                  )}
                  statusLabel={meta.label}
                  statusCategory={meta.category}
                />
              )
            })}
          </div>

          {(page > 0 || hasNextPage) && (
            <div className="flex items-center justify-end gap-2">
              <span className="mr-auto text-xs text-muted-foreground">Page {page + 1}</span>
              <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
                Previous
              </Button>
              <Button variant="outline" size="sm" disabled={!hasNextPage} onClick={() => setPage((p) => p + 1)}>
                Next
              </Button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
