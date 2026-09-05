'use client'

export const dynamic = 'force-dynamic'

import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams } from 'next/navigation'
import { BrainIcon, LibraryIcon, SearchIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage, listAgents, type Agent, type ApiError } from '@/lib/api'
import {
  createMemory,
  deleteMemory,
  getMemory,
  getMemoryCounts,
  listMemories,
  updateMemory,
  type MemoryCounts,
  type MemoryDetailView,
  type MemoryStatus,
  type MemoryType,
  type MemoryView,
} from '@/lib/memory-api'
import { timeAgo, formatDate } from '@/lib/format'
import { cn } from '@/lib/utils'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'
import { StatusBadge, type StatusHue } from '@/components/ui/status-badge'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'
import { Alert } from '@/components/ui/alert'
import { Modal } from '@/components/ui/modal'
import { Sheet } from '@/components/ui/sheet'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { useToast } from '@/components/ui/toast'
import { AgentAvatar } from '@/components/agents/AgentAvatar'

const MEMORY_TYPES: MemoryType[] = ['fact', 'decision', 'preference', 'event']
const MEMORY_STATUSES: MemoryStatus[] = ['raw', 'active', 'superseded']
const PAGE_SIZE = 50

// Domain-local status styling: memory's derived tri-state ids are common English words ("raw",
// "superseded") that must not enter the app-wide WELL_KNOWN_HUES map — a Workflow could name a Work
// Item status "Raw" and would inherit memory's styling. Hue passed explicitly to StatusBadge instead.
const MEMORY_STATUS_HUES: Record<MemoryStatus, StatusHue> = { raw: 'amber', active: 'green', superseded: 'slate' }
const MEMORY_STATUS_LABELS: Record<MemoryStatus, string> = { raw: 'Raw', active: 'Active', superseded: 'Superseded' }

export default function MemoryPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const { showToast } = useToast()

  const [items, setItems] = useState<MemoryView[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [loadingMore, setLoadingMore] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [counts, setCounts] = useState<MemoryCounts | null>(null)
  const [agentsById, setAgentsById] = useState<Record<string, Agent>>({})

  const [searchInput, setSearchInput] = useState('')
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<MemoryStatus | ''>('')
  const [typeFilter, setTypeFilter] = useState<MemoryType | ''>('')

  const [addOpen, setAddOpen] = useState(false)
  const [addContent, setAddContent] = useState('')
  const [addType, setAddType] = useState<MemoryType>('fact')
  const [addImportance, setAddImportance] = useState(5)
  const [addError, setAddError] = useState<string | null>(null)
  const [addSubmitting, setAddSubmitting] = useState(false)

  const [detailId, setDetailId] = useState<string | null>(null)
  const [detail, setDetail] = useState<MemoryDetailView | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState<string | null>(null)

  const [editing, setEditing] = useState(false)
  const [editContent, setEditContent] = useState('')
  const [editType, setEditType] = useState<MemoryType>('fact')
  const [editImportance, setEditImportance] = useState(5)
  const [editError, setEditError] = useState<string | null>(null)
  const [editSubmitting, setEditSubmitting] = useState(false)

  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false)
  const [deleteSubmitting, setDeleteSubmitting] = useState(false)

  // Debounce the raw search box into `query`, only firing once at least 2 characters are typed
  // (matches KnowledgeSearch's convention) — an empty box clears the filter immediately.
  useEffect(() => {
    const timer = setTimeout(() => {
      const trimmed = searchInput.trim()
      setQuery(trimmed.length >= 2 ? trimmed : '')
    }, 400)
    return () => clearTimeout(timer)
  }, [searchInput])

  const refreshCounts = useCallback(() => {
    if (!accessToken || !projectId) return
    getMemoryCounts(projectId, accessToken).then(setCounts).catch(() => {})
  }, [projectId, accessToken])

  useEffect(() => {
    refreshCounts()
  }, [refreshCounts])

  useEffect(() => {
    if (!accessToken || !projectId) return
    listAgents(projectId, accessToken)
      .then((agents) => setAgentsById(Object.fromEntries(agents.map((a) => [a.id, a]))))
      .catch(() => {})
  }, [projectId, accessToken])

  // Monotonic request sequence shared by loadMemories and handleLoadMore: a response only lands if no
  // newer list request has started since, so a slow response for an old filter/search combination can
  // never overwrite (or append into) results for the current one.
  const listReqSeq = useRef(0)

  const loadMemories = useCallback(() => {
    if (!accessToken || !projectId) return
    const seq = ++listReqSeq.current
    setLoading(true)
    setError(null)
    listMemories(projectId, accessToken, {
      q: query || undefined,
      status: statusFilter || undefined,
      type: typeFilter || undefined,
      limit: PAGE_SIZE,
      offset: 0,
    })
      .then((res) => {
        if (seq !== listReqSeq.current) return
        setItems(res.items)
        setTotal(res.total)
      })
      .catch((err) => {
        if (seq !== listReqSeq.current) return
        setError(apiErrorMessage(err, 'Failed to load memories.'))
      })
      .finally(() => {
        if (seq === listReqSeq.current) setLoading(false)
      })
  }, [projectId, accessToken, query, statusFilter, typeFilter])

  useEffect(() => {
    loadMemories()
  }, [loadMemories])

  async function handleLoadMore() {
    if (!accessToken || !projectId) return
    const seq = listReqSeq.current
    setLoadingMore(true)
    try {
      const res = await listMemories(projectId, accessToken, {
        q: query || undefined,
        status: statusFilter || undefined,
        type: typeFilter || undefined,
        limit: PAGE_SIZE,
        offset: items.length,
      })
      if (seq !== listReqSeq.current) return // filters changed mid-flight; these rows belong to the old query
      // Offset pagination over a continuously-written, created_at-DESC table: a row inserted between
      // pages shifts the window, so a page can re-return rows already shown. Dedupe by id on append.
      setItems((prev) => {
        const seen = new Set(prev.map((m) => m.id))
        return [...prev, ...res.items.filter((m) => !seen.has(m.id))]
      })
      setTotal(res.total)
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to load more memories.'), 'error')
    } finally {
      setLoadingMore(false)
    }
  }

  function openAdd() {
    setAddContent('')
    setAddType('fact')
    setAddImportance(5)
    setAddError(null)
    setAddOpen(true)
  }

  async function handleAdd(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken || !projectId) return
    const content = addContent.trim()
    if (!content) {
      setAddError('Content is required.')
      return
    }
    setAddSubmitting(true)
    setAddError(null)
    try {
      await createMemory(projectId, { content, type: addType, importance: addImportance }, accessToken)
      setAddOpen(false)
      // Re-query instead of splicing the created row in locally: the new memory may not match the
      // active status/type/search filters, and the server owns the sort order.
      loadMemories()
      refreshCounts()
      showToast('Memory created')
    } catch (err) {
      setAddError(apiErrorMessage(err, 'Failed to create memory.'))
    } finally {
      setAddSubmitting(false)
    }
  }

  function openDetail(id: string) {
    setDetailId(id)
    setDetail(null)
    setDetailError(null)
    setEditing(false)
  }

  useEffect(() => {
    if (!detailId || !accessToken || !projectId) return
    let stale = false // a newer detail opened (or the sheet closed) before this response landed
    setDetailLoading(true)
    setDetailError(null)
    getMemory(projectId, detailId, accessToken)
      .then((d) => {
        if (!stale) setDetail(d)
      })
      .catch((err) => {
        if (!stale) setDetailError(apiErrorMessage(err, 'Failed to load memory.'))
      })
      .finally(() => {
        if (!stale) setDetailLoading(false)
      })
    return () => {
      stale = true
    }
  }, [detailId, projectId, accessToken])

  function closeDetail() {
    setDetailId(null)
    setDetail(null)
    setEditing(false)
    setDeleteConfirmOpen(false)
  }

  function startEdit() {
    if (!detail) return
    setEditContent(detail.content)
    setEditType(detail.type)
    setEditImportance(detail.importance)
    setEditError(null)
    setEditing(true)
  }

  async function handleSaveEdit(e: React.FormEvent) {
    e.preventDefault()
    if (!accessToken || !projectId || !detail) return
    const content = editContent.trim()
    if (!content) {
      setEditError('Content is required.')
      return
    }
    setEditSubmitting(true)
    setEditError(null)
    try {
      const updated = await updateMemory(
        projectId,
        detail.id,
        { content, type: editType, importance: editImportance },
        accessToken,
      )
      setDetail((prev) => (prev ? { ...prev, ...updated } : prev))
      // Re-query like the create path: an edit can change `type` out from under an active type filter,
      // and the server owns the sort order.
      loadMemories()
      setEditing(false)
      showToast('Memory updated')
    } catch (err) {
      const status = (err as ApiError).status
      if (status === 409) {
        // Superseded by consolidation after the sheet was opened; the client-side disable was stale.
        setEditError('This memory was superseded while you were editing — close and reopen it to see the latest version.')
      } else if (status === 404) {
        showToast('This memory no longer exists.', 'error')
        closeDetail()
        loadMemories()
        refreshCounts()
      } else {
        setEditError(apiErrorMessage(err, 'Failed to update memory.'))
      }
    } finally {
      setEditSubmitting(false)
    }
  }

  async function handleDelete() {
    if (!accessToken || !projectId || !detail) return
    setDeleteSubmitting(true)
    try {
      await deleteMemory(projectId, detail.id, accessToken)
      setItems((prev) => prev.filter((m) => m.id !== detail.id))
      setTotal((t) => Math.max(0, t - 1))
      refreshCounts()
      showToast('Memory deleted')
      closeDetail()
    } catch (err) {
      if ((err as ApiError).status === 404) {
        // Already gone (deleted elsewhere or purged) -- the desired end state, so just resync.
        closeDetail()
        loadMemories()
        refreshCounts()
      } else {
        showToast(apiErrorMessage(err, 'Failed to delete memory.'), 'error')
      }
    } finally {
      setDeleteSubmitting(false)
    }
  }

  const countsSummary = counts
    ? `${counts.liveTotal} ${counts.liveTotal === 1 ? 'memory' : 'memories'} · ${counts.raw} awaiting consolidation · ${counts.superseded} superseded`
    : undefined

  return (
    <PageContainer>
      <PageHeader
        title="Memory"
        description={
          <>
            What agents durably remember from past conversations — facts, decisions, preferences, and events.
            {countsSummary && <span className="block mt-0.5">{countsSummary}</span>}
          </>
        }
        actions={<Button onClick={openAdd}>Add memory</Button>}
      />

      <div className="flex flex-wrap items-center gap-2 mb-4">
        <div className="relative flex-1 min-w-[220px] max-w-sm">
          <SearchIcon className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-subtle" />
          <Input
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Search memory content..."
            className="pl-8"
            aria-label="Search memory content"
          />
        </div>
        <Select
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value as MemoryStatus | '')}
          aria-label="Filter by status"
          className="w-auto"
        >
          <option value="">All statuses</option>
          {MEMORY_STATUSES.map((s) => (
            <option key={s} value={s}>
              {s === 'raw' ? 'Raw' : s === 'active' ? 'Active' : 'Superseded'}
            </option>
          ))}
        </Select>
        <Select
          value={typeFilter}
          onChange={(e) => setTypeFilter(e.target.value as MemoryType | '')}
          aria-label="Filter by type"
          className="w-auto"
        >
          <option value="">All types</option>
          {MEMORY_TYPES.map((t) => (
            <option key={t} value={t} className="capitalize">
              {t.charAt(0).toUpperCase() + t.slice(1)}
            </option>
          ))}
        </Select>
      </div>

      {loading ? (
        <div className="space-y-2">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      ) : error ? (
        <Alert variant="destructive">
          <div className="flex items-center justify-between gap-3">
            <p>{error}</p>
            <Button size="sm" variant="outline" onClick={loadMemories}>
              Retry
            </Button>
          </div>
        </Alert>
      ) : items.length === 0 ? (
        <Card>
          <EmptyState
            icon={BrainIcon}
            title="No memories yet"
            description="Agents automatically remember durable facts and decisions as conversations happen."
            action={<Button size="sm" onClick={openAdd}>Add memory</Button>}
          />
        </Card>
      ) : (
        <>
          <Card>
            <CardContent>
              {items.map((m) => {
                const agent = m.agentId ? agentsById[m.agentId] : undefined
                const superseded = m.status === 'superseded'
                return (
                  <button
                    key={m.id}
                    type="button"
                    onClick={() => openDetail(m.id)}
                    className="w-full text-left px-4 py-3 hover:bg-muted/50 transition-colors flex flex-col gap-1.5"
                  >
                    <div className="flex items-start justify-between gap-3">
                      <p className={cn('text-sm flex-1 line-clamp-2', superseded && 'text-muted-foreground')}>
                        {m.content}
                      </p>
                      <span className="text-xs text-muted-foreground shrink-0 whitespace-nowrap">
                        {timeAgo(m.createdAt)}
                      </span>
                    </div>
                    <div className="flex items-center gap-2 flex-wrap">
                      <Badge variant="secondary" className="capitalize">{m.type}</Badge>
                      <StatusBadge status={m.status} hue={MEMORY_STATUS_HUES[m.status]} label={MEMORY_STATUS_LABELS[m.status]} />
                      <span className="text-xs text-muted-foreground">Importance {m.importance}/10</span>
                      {agent && (
                        <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                          <AgentAvatar emoji={agent.avatarEmoji} color={agent.avatarColor} size="sm" />
                          {agent.name}
                        </span>
                      )}
                      {m.promotedAt && (
                        <span className="inline-flex items-center gap-1 text-xs text-status-approved">
                          <LibraryIcon className="h-3 w-3" />
                          Promoted to Knowledge
                        </span>
                      )}
                    </div>
                  </button>
                )
              })}
            </CardContent>
          </Card>
          <div className="flex items-center justify-between mt-3 text-sm text-muted-foreground">
            <span>
              Showing {items.length} of {total}
            </span>
            {items.length < total && (
              <Button size="sm" variant="outline" onClick={handleLoadMore} disabled={loadingMore}>
                {loadingMore ? 'Loading…' : 'Load more'}
              </Button>
            )}
          </div>
        </>
      )}

      {/* ── Add memory ─────────────────────────────────────────────────────── */}
      <Modal open={addOpen} onOpenChange={(o) => { if (!o) setAddOpen(false) }} title="Add memory">
        <form onSubmit={handleAdd} className="space-y-4">
          <div>
            <Label htmlFor="memory-content">Content</Label>
            <Textarea
              id="memory-content"
              value={addContent}
              onChange={(e) => setAddContent(e.target.value)}
              placeholder="A durable fact, decision, preference, or event worth remembering."
              rows={4}
              maxLength={2000}
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label htmlFor="memory-type">Type</Label>
              <Select id="memory-type" value={addType} onChange={(e) => setAddType(e.target.value as MemoryType)}>
                {MEMORY_TYPES.map((t) => (
                  <option key={t} value={t}>{t.charAt(0).toUpperCase() + t.slice(1)}</option>
                ))}
              </Select>
            </div>
            <div>
              <Label htmlFor="memory-importance">Importance (1-10)</Label>
              <Select
                id="memory-importance"
                value={String(addImportance)}
                onChange={(e) => setAddImportance(Number(e.target.value))}
              >
                {Array.from({ length: 10 }, (_, i) => i + 1).map((n) => (
                  <option key={n} value={n}>{n}</option>
                ))}
              </Select>
            </div>
          </div>
          {addError && <p className="text-sm text-destructive" role="alert">{addError}</p>}
          <div className="flex gap-3">
            <Button type="submit" disabled={addSubmitting}>
              {addSubmitting ? 'Adding…' : 'Add memory'}
            </Button>
            <Button type="button" variant="outline" onClick={() => setAddOpen(false)} disabled={addSubmitting}>
              Cancel
            </Button>
          </div>
        </form>
      </Modal>

      {/* ── Detail ──────────────────────────────────────────────────────────── */}
      <Sheet open={detailId !== null} onOpenChange={(o) => { if (!o) closeDetail() }} title="Memory">
        {detailLoading ? (
          <div className="space-y-3">
            <Skeleton className="h-20 w-full" />
            <Skeleton className="h-4 w-2/3" />
          </div>
        ) : detailError ? (
          <Alert variant="destructive">{detailError}</Alert>
        ) : detail ? (
          <div className="space-y-5">
            {editing ? (
              <form onSubmit={handleSaveEdit} className="space-y-4">
                <div>
                  <Label htmlFor="edit-memory-content">Content</Label>
                  <Textarea
                    id="edit-memory-content"
                    value={editContent}
                    onChange={(e) => setEditContent(e.target.value)}
                    rows={4}
                    maxLength={2000}
                  />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <Label htmlFor="edit-memory-type">Type</Label>
                    <Select id="edit-memory-type" value={editType} onChange={(e) => setEditType(e.target.value as MemoryType)}>
                      {MEMORY_TYPES.map((t) => (
                        <option key={t} value={t}>{t.charAt(0).toUpperCase() + t.slice(1)}</option>
                      ))}
                    </Select>
                  </div>
                  <div>
                    <Label htmlFor="edit-memory-importance">Importance (1-10)</Label>
                    <Select
                      id="edit-memory-importance"
                      value={String(editImportance)}
                      onChange={(e) => setEditImportance(Number(e.target.value))}
                    >
                      {Array.from({ length: 10 }, (_, i) => i + 1).map((n) => (
                        <option key={n} value={n}>{n}</option>
                      ))}
                    </Select>
                  </div>
                </div>
                {editError && <p className="text-sm text-destructive" role="alert">{editError}</p>}
                <div className="flex gap-3">
                  <Button type="submit" size="sm" disabled={editSubmitting}>
                    {editSubmitting ? 'Saving…' : 'Save'}
                  </Button>
                  <Button type="button" size="sm" variant="outline" onClick={() => setEditing(false)} disabled={editSubmitting}>
                    Cancel
                  </Button>
                </div>
              </form>
            ) : (
              <>
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge variant="secondary" className="capitalize">{detail.type}</Badge>
                  <StatusBadge status={detail.status} hue={MEMORY_STATUS_HUES[detail.status]} label={MEMORY_STATUS_LABELS[detail.status]} />
                  <span className="text-xs text-muted-foreground">Importance {detail.importance}/10</span>
                </div>
                <p className="text-sm whitespace-pre-wrap">{detail.content}</p>

                <dl className="text-xs text-muted-foreground space-y-1">
                  {detail.agentId && (
                    <div className="flex gap-1.5">
                      <dt className="font-medium">Source agent:</dt>
                      <dd>{agentsById[detail.agentId]?.name ?? detail.agentId}</dd>
                    </div>
                  )}
                  <div className="flex gap-1.5">
                    <dt className="font-medium">Created:</dt>
                    <dd>{formatDate(detail.createdAt)}</dd>
                  </div>
                  <div className="flex gap-1.5">
                    <dt className="font-medium">Valid from:</dt>
                    <dd>{formatDate(detail.validFrom)}</dd>
                  </div>
                  {detail.validTo && (
                    <div className="flex gap-1.5">
                      <dt className="font-medium">Valid to:</dt>
                      <dd>{formatDate(detail.validTo)}</dd>
                    </div>
                  )}
                  {detail.promotedAt && (
                    <div className="flex gap-1.5 items-center">
                      <dt className="font-medium">Promoted to Knowledge:</dt>
                      <dd className="flex items-center gap-1 text-status-approved">
                        <LibraryIcon className="h-3 w-3" />
                        {formatDate(detail.promotedAt)}
                      </dd>
                    </div>
                  )}
                  <div className="flex gap-1.5">
                    <dt className="font-medium">Accessed:</dt>
                    <dd>
                      {detail.accessCount} time{detail.accessCount === 1 ? '' : 's'}
                      {detail.lastAccessedAt ? ` · last ${timeAgo(detail.lastAccessedAt)}` : ''}
                    </dd>
                  </div>
                </dl>

                {detail.status === 'superseded' && (
                  <Alert variant="warning">
                    {detail.supersededBy
                      ? 'This memory has been superseded by a newer version, so it can no longer be edited.'
                      : 'This memory aged out — it went unused long enough that retention closed it, so it can no longer be edited.'}
                  </Alert>
                )}

                {detail.history.length > 0 && (
                  <div>
                    <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground mb-2">
                      Supersession history
                    </h3>
                    <ul className="space-y-3 border-l border-border pl-3">
                      {detail.history.map((h) => (
                        <li key={h.id} className="text-xs text-muted-foreground">
                          <div className="flex items-center gap-2 flex-wrap">
                            <span className="font-medium text-foreground">{formatDate(h.createdAt)}</span>
                            <Badge variant="secondary" className="capitalize">{h.type}</Badge>
                          </div>
                          <p className="mt-0.5 line-clamp-3">{h.content}</p>
                        </li>
                      ))}
                    </ul>
                  </div>
                )}

                <div className="flex gap-3 pt-2">
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={startEdit}
                    disabled={detail.status === 'superseded'}
                    title={detail.status === 'superseded' ? 'Superseded memories are read-only history' : undefined}
                  >
                    Edit
                  </Button>
                  <Button
                    size="sm"
                    variant="ghost"
                    className="text-destructive hover:text-destructive hover:bg-destructive/10"
                    onClick={() => setDeleteConfirmOpen(true)}
                  >
                    Delete
                  </Button>
                </div>
              </>
            )}
          </div>
        ) : null}
      </Sheet>

      <ConfirmModal
        open={deleteConfirmOpen}
        title="Delete memory"
        description="Permanently delete this memory? This cannot be undone."
        confirmLabel="Delete"
        busyLabel="Deleting…"
        busy={deleteSubmitting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteConfirmOpen(false)}
      />
    </PageContainer>
  )
}
