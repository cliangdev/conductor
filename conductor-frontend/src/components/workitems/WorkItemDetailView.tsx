'use client'

// COND-22: the generic Work Item detail view, rendered under the workflow-scoped
// /{area}/{nouns}/{displayId} route. It is keyed by the Work Item UUID
// (all sub-resource fetches use the canonical /api/v2/.../work-items/{workItemId}/... endpoints); the
// bound Workflow slug arrives as a prop (no more useParams/DEFAULT_WORKFLOW_SLUG fallback).
//
// Redesigned (design-system.md) to join the shared page chrome: PageContainer/PageHeader, a
// document-tabs + Activity reading column, and a right properties panel. Review is the GitHub-style
// batch model — see ReviewBar and the pending-comment state below.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, apiPost, apiDelete, apiErrorMessage } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { WorkItemDetailSkeleton } from '@/components/workitems/WorkItemDetailSkeleton'
import { WorkItemPropertiesPanel } from '@/components/workitems/WorkItemPropertiesPanel'
import { MediaUploadPanel, type MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { WorkItemDescriptionCard } from '@/components/workitems/WorkItemDescriptionCard'
import { PostTargetPicker, workflowDeclaresPublishTargets } from '@/components/marketing/PostTargetPicker'
import { PublishOutcomePanel } from '@/components/marketing/PublishOutcomePanel'
import {
  TikTokConsentStep,
  TikTokPublishGateProvider,
  tiktokSubmissionBlockedReason,
  type TikTokConsentTarget,
} from '@/components/marketing/TikTokConsentStep'
import { ActivityTab } from '@/components/workitems/ActivityTab'
import { toastError, toastSuccess } from '@/components/ui/toast'
import { ExternalLink, FileText, FileX2 } from 'lucide-react'
import { CommentableDocument } from '@/components/comments/CommentableDocument'
import { ReviewBar } from '@/components/reviews/ReviewBar'
import { StatusBadge } from '@/components/ui/status-badge'
import { CommentCount } from '@/components/ui/comment-count'
import { HtmlViewer } from '@/components/markdown/HtmlViewer'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader, type Crumb } from '@/components/layout/PageHeader'
import { registerPaletteActions } from '@/components/layout/CommandPalette'
import { openMenuTrigger } from '@/components/workitems/useWorkItemListState'
import { timeAgo } from '@/lib/format'
import { readPersistedJSON, writePersistedJSON, removePersisted } from '@/lib/persisted'
import { cn } from '@/lib/utils'
import {
  humanizeId,
  pluralizeNoun,
  reviewGateForStatus,
  statusHasReviewGate,
  statusMeta,
  useWorkflowView,
  workItemListPath,
} from '@/lib/workflows'
import type { Comment, PendingCommentDraft } from '@/components/comments/types'
import type { DetailDocument, DetailIssue, DetailReview, DetailReviewer } from '@/components/workitems/detailTypes'
import type { Member } from '@/components/workitems/listTypes'
import type { Verdict } from '@/components/reviews/verdict'
import type { MemberRole } from '@/types'

function isMarkdown(doc: DetailDocument): boolean {
  if (doc.filename.endsWith('.html') || doc.filename.endsWith('.htm')) return false
  return (
    doc.contentType === 'text/markdown' ||
    doc.contentType === 'text/plain' ||
    doc.filename.endsWith('.md')
  )
}

function isHtml(doc: DetailDocument): boolean {
  return (
    doc.contentType === 'text/html' ||
    doc.filename.endsWith('.html') ||
    doc.filename.endsWith('.htm')
  )
}

function isExpiringSoon(doc: DetailDocument): boolean {
  if (!doc.storageUrlExpiresAt) return false
  const expiresAt = new Date(doc.storageUrlExpiresAt).getTime()
  return expiresAt - Date.now() < 60_000
}

// Scoped to both the Work Item and the signed-in user — a shared browser (or a handoff between
// accounts) must never surface one reviewer's drafted comments to another. No migration/cleanup of
// the old unscoped key is needed: it simply stops being read, and stays silently orphaned.
function reviewDraftKey(workItemId: string, userId: string): string {
  return `wi_review_${workItemId}_${userId}`
}

interface ReviewDraft {
  /** Bumped if the persisted shape ever changes — readPersistedJSON's validator rejects a mismatch
   * (or a pre-versioning draft) instead of handing back a shape the rest of this file assumes. */
  version: 1
  active: boolean
  pending: PendingCommentDraft[]
}

function isPendingCommentDraft(v: unknown): v is PendingCommentDraft {
  if (!v || typeof v !== 'object') return false
  const d = v as Record<string, unknown>
  return (
    typeof d.localId === 'string' &&
    typeof d.documentId === 'string' &&
    typeof d.lineNumber === 'number' &&
    typeof d.content === 'string'
  )
}

function isValidReviewDraft(v: unknown): v is ReviewDraft {
  if (!v || typeof v !== 'object') return false
  const d = v as Record<string, unknown>
  return (
    d.version === 1 &&
    typeof d.active === 'boolean' &&
    Array.isArray(d.pending) &&
    d.pending.every(isPendingCommentDraft)
  )
}

const EMPTY_REVIEW_DRAFT: ReviewDraft = { version: 1, active: false, pending: [] }

function persistReviewDraft(draftKey: string, active: boolean, pending: PendingCommentDraft[]) {
  if (active || pending.length > 0) {
    writePersistedJSON<ReviewDraft>(draftKey, { version: 1, active, pending })
  } else {
    removePersisted(draftKey)
  }
}

/** Drops drafts whose document was removed, or whose line number has fallen past the document's
 * current length (edited out from under the draft) — checked both at hydration and just before
 * submit so a stale draft can never silently post against a line that no longer means what it did
 * when the comment was drafted. */
function dropStaleDrafts(
  pending: PendingCommentDraft[],
  docs: DetailDocument[]
): { valid: PendingCommentDraft[]; droppedCount: number } {
  const docsById = new Map(docs.map((d) => [d.id, d]))
  const valid = pending.filter((p) => {
    const doc = docsById.get(p.documentId)
    if (!doc) return false
    if (typeof doc.content !== 'string') return true // not loaded — can't check line count, keep it
    return p.lineNumber >= 1 && p.lineNumber <= doc.content.split('\n').length
  })
  return { valid, droppedCount: pending.length - valid.length }
}

let pendingIdCounter = 0
function nextPendingId(): string {
  pendingIdCounter += 1
  return `pending-${Date.now()}-${pendingIdCounter}`
}

const TAB_CLASSES =
  'inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium border-b-2 -mb-px transition-colors whitespace-nowrap'

function unresolvedCountForDocument(comments: Comment[], documentId: string): number {
  return comments.filter((c) => c.documentId === documentId && !c.resolvedAt).length
}

/** Comments on the item itself rather than a line of one of its documents — these live in Activity. */
function unresolvedItemLevelCount(comments: Comment[]): number {
  return comments.filter((c) => c.lineNumber == null && !c.resolvedAt).length
}

/**
 * Unresolved comment count on a document tab — without it there's no way to tell which of an item's
 * documents someone commented on short of opening each and scanning its gutter. The count is repeated
 * in the tab's accessible name, so this copy is `aria-hidden` and never the sole carrier.
 */
function tabCommentCount(count: number, active: boolean) {
  return (
    <span aria-hidden="true">
      <CommentCount count={count} className={active ? 'text-primary' : 'text-muted-foreground'} />
    </span>
  )
}

/**
 * The stand-in tab for a Work Item with no documents yet.
 *
 * Not a document and not a real panel of its own — it selects the same main panel the document viewer
 * uses, which is where the caption, media and accounts live. It exists so the tab bar is never a
 * one-way trip into Activity.
 */
const CONTENT_TAB = 'content'

// ARIA tabs pattern ids — the same tab id (a document id, 'activity', or 'details') derives both the
// tab button's id and the panel it controls, so the two stay in sync without a second id map.
function tabButtonId(tabId: string): string {
  return `wi-tab-${tabId}`
}

/**
 * Work Item detail view, keyed by the Work Item UUID. The bound Workflow slug is passed in (drives the
 * status badge, the review bar, and the breadcrumb trail).
 */
export function WorkItemDetailView({
  projectId,
  workItemId,
  slug,
}: {
  projectId: string
  workItemId: string
  slug: string
}) {
  // Keep the legacy internal name — all /api/v1 sub-resource fetches are UUID-keyed on this id.
  const issueId = workItemId
  const { accessToken, user } = useAuth()

  const [issue, setIssue] = useState<DetailIssue | null>(null)
  const [documents, setDocuments] = useState<DetailDocument[]>([])
  const [assets, setAssets] = useState<MediaAsset[]>([])
  // TIK-2. TikTok's Content Sharing Guidelines require the creator to see the content and the
  // account nickname it posts to, and to consent, before anything is uploaded — so consent is held
  // here, beside the media the preview is built from, and published as a gate the status control
  // reads. It is deliberately not persisted: it is this person, agreeing to this content, now.
  const [tiktokTargets, setTikTokTargets] = useState<TikTokConsentTarget[]>([])
  const [consentedTo, setConsentedTo] = useState<string | null>(null)
  // Tags already in use in this project, for the editor's suggestions. Read from the Work Item list —
  // there is no separate tag registry, and a project's tags are exactly the ones on its items.
  const [knownTags, setKnownTags] = useState<string[]>([])
  const [reviewers, setReviewers] = useState<DetailReviewer[]>([])
  const [reviews, setReviews] = useState<DetailReview[]>([])
  const [userRole, setUserRole] = useState<MemberRole>('REVIEWER')
  const [allMembers, setAllMembers] = useState<Member[]>([])
  const [selectedDocId, setSelectedDocId] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<string>('')
  const [comments, setComments] = useState<Comment[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // ── Batch review mode (COND-22) ────────────────────────────────────────────
  const [reviewMode, setReviewMode] = useState(false)
  const [pendingComments, setPendingComments] = useState<PendingCommentDraft[]>([])
  const [reviewSubmitting, setReviewSubmitting] = useState(false)
  const [cancelConfirmOpen, setCancelConfirmOpen] = useState(false)

  const statusTriggerRef = useRef<HTMLButtonElement>(null)
  const assigneeTriggerRef = useRef<HTMLButtonElement>(null)
  const hydratedDraftRef = useRef(false)

  // Scoped to this Work Item + the signed-in user; null until the user is known, which the hydration
  // effect below waits on rather than reading (and thus scoping) a draft too early.
  const draftKey = user?.id ? reviewDraftKey(issueId, user.id) : null

  // Display metadata for the Work Item's Workflow (resolved by the slug prop). Drives the status badge,
  // whether the review bar is offered, and the breadcrumb area/noun.
  const workflowSlug = slug
  const workflowView = useWorkflowView(projectId, workflowSlug, accessToken)

  const fetchDocuments = useCallback(async () => {
    if (!accessToken) return
    const docs = await apiGet<DetailDocument[]>(
      `/api/v2/projects/${projectId}/work-items/${issueId}/documents`,
      accessToken
    )
    setDocuments(docs.filter((d) => d.filename !== 'tasks.json'))
    setSelectedDocId((prev) => {
      if (prev) return prev
      const firstMd = docs.find(isMarkdown)
      return firstMd?.id ?? docs[0]?.id ?? null
    })
  }, [accessToken, projectId, issueId])

  const fetchComments = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<Comment[]>(
        `/api/v2/projects/${projectId}/work-items/${issueId}/comments`,
        accessToken
      )
      setComments(data)
    } catch {
      // Non-fatal
    }
  }, [accessToken, projectId, issueId])

  // Suggestions come from what the project already uses, so tagging converges on a vocabulary rather
  // than accumulating near-duplicates nobody can filter by. Non-fatal: no suggestions is a fine state.
  const fetchKnownTags = useCallback(async () => {
    if (!accessToken) return
    try {
      const items = await apiGet<{ tags?: string[] }[]>(
        `/api/v2/projects/${projectId}/work-items`,
        accessToken
      )
      setKnownTags([...new Set(items.flatMap((i) => i.tags ?? []))].sort())
    } catch {
      // Non-fatal — the field still accepts anything typed.
    }
  }, [accessToken, projectId])

  const fetchReviewers = useCallback(async () => {
    if (!accessToken) return
    const data = await apiGet<DetailReviewer[]>(
      `/api/v2/projects/${projectId}/work-items/${issueId}/reviewers`,
      accessToken
    )
    setReviewers(data)
  }, [accessToken, projectId, issueId])

  const fetchReviews = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<DetailReview[]>(
        `/api/v2/projects/${projectId}/work-items/${issueId}/reviews`,
        accessToken
      )
      setReviews(data)
    } catch {
      // Non-fatal
    }
  }, [accessToken, projectId, issueId])

  const fetchAssets = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<MediaAsset[]>(
        `/api/v2/projects/${projectId}/work-items/${issueId}/assets`,
        accessToken
      )
      setAssets(data)
    } catch {
      // Non-fatal — the assets list simply renders nothing.
    }
  }, [accessToken, projectId, issueId])

  // Editing a Post's publish bundle can revert it out of Approved (PublishBundleGuard), so the status
  // chip and the review panel have to be re-read after the picker saves rather than assumed unchanged.
  const refreshIssueStatus = useCallback(async () => {
    if (!accessToken) return
    try {
      const data = await apiGet<DetailIssue>(
        `/api/v2/projects/${projectId}/work-items/${issueId}`,
        accessToken
      )
      setIssue(data)
    } catch {
      // Non-fatal — a stale chip corrects itself on the next load.
    }
    await fetchReviews()
  }, [accessToken, projectId, issueId, fetchReviews])

  useEffect(() => {
    if (!accessToken) return

    async function fetchAll() {
      try {
        const [issueData, reviewerData] = await Promise.all([
          apiGet<DetailIssue>(`/api/v2/projects/${projectId}/work-items/${issueId}`, accessToken!),
          apiGet<DetailReviewer[]>(
            `/api/v2/projects/${projectId}/work-items/${issueId}/reviewers`,
            accessToken!
          ),
        ])
        setIssue(issueData)
        setReviewers(reviewerData)

        await Promise.all([fetchDocuments(), fetchComments(), fetchReviews(), fetchAssets(), fetchKnownTags()])

        try {
          const members = await apiGet<Member[]>(
            `/api/v1/projects/${projectId}/members`,
            accessToken!
          )
          setAllMembers(members)
          const currentMember = members.find((m) => m.userId === user?.id)
          if (currentMember) setUserRole(currentMember.role)
        } catch {
          // Default to REVIEWER
        }
      } catch (err) {
        setError(apiErrorMessage(err, 'Failed to load work item'))
      } finally {
        setLoading(false)
      }
    }

    fetchAll()
  }, [accessToken, projectId, issueId, fetchDocuments, fetchComments, fetchReviews, fetchAssets, user?.id])

  useEffect(() => {
    if (documents.length === 0) return
    const expiring = documents.filter(isExpiringSoon)
    if (expiring.length === 0) return
    fetchDocuments().catch(() => {})
  }, [documents, fetchDocuments])

  // Default the active tab to the initially-selected document, once — doesn't fight the user's own
  // later tab clicks (activeTab is only empty before the first load).
  useEffect(() => {
    if (!activeTab && selectedDocId) setActiveTab(selectedDocId)
  }, [activeTab, selectedDocId])

  const selectedDoc = documents.find((d) => d.id === selectedDocId) ?? null

  function selectDocument(doc: DetailDocument) {
    if (isMarkdown(doc) || isHtml(doc)) {
      setSelectedDocId(doc.id)
      setActiveTab(doc.id)
    } else if (doc.storageUrl) {
      window.open(doc.storageUrl, '_blank', 'noopener,noreferrer')
    }
  }

  // ── Document tabs: roving tabindex + ArrowLeft/Right (WAI-ARIA tabs pattern) ────────────────────
  const tabButtonRefs = useRef(new Map<string, HTMLButtonElement>())

  function activateTab(tabId: string) {
    const doc = documents.find((d) => d.id === tabId)
    if (doc) selectDocument(doc)
    else setActiveTab(tabId)
  }

  function handleTabKeyDown(e: React.KeyboardEvent, tabIds: string[], currentId: string) {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
    e.preventDefault()
    const idx = tabIds.indexOf(currentId)
    const delta = e.key === 'ArrowRight' ? 1 : -1
    const nextId = tabIds[(idx + delta + tabIds.length) % tabIds.length]
    tabButtonRefs.current.get(nextId)?.focus()
    activateTab(nextId)
  }

  const mediaAssets = useMemo(() => assets.filter((a) => a.kind === 'file'), [assets])

  // Consent is to *this* content going to *these* accounts under *these* options, so it is stored
  // as the thing consented to rather than as a bare flag. Swap an account, edit a privacy level or
  // upload a different cut and it stops matching — which is the point: what was agreed to no longer
  // exists, so it has to be agreed to again before the Post can move.
  const consentSubject = useMemo(
    () => JSON.stringify([tiktokTargets, mediaAssets.map((a) => a.id)]),
    [tiktokTargets, mediaAssets]
  )
  const tiktokConsented = consentedTo === consentSubject

  const tiktokBlockedReason = tiktokSubmissionBlockedReason(tiktokTargets, tiktokConsented)

  const isAssignedReviewer = reviewers.some((r) => r.userId === user?.id)
  const canManage = userRole === 'CREATOR' || userRole === 'ADMIN'

  // Review is only relevant when the current status has an outgoing review-gated transition. When it
  // doesn't, the review bar and the assign-reviewer affordance are both hidden.
  const reviewActive = issue ? statusHasReviewGate(workflowView, issue.status) : false
  const reviewOutcomes = issue
    ? reviewGateForStatus(workflowView, issue.status)?.reviewOutcomes
    : undefined

  const assignedIds = new Set(reviewers.map((r) => r.userId))
  const assignableReviewers = allMembers.filter(
    (m) => m.role === 'REVIEWER' && !assignedIds.has(m.userId)
  )

  // Hydrate any in-progress review draft for this Work Item + user (localStorage) once the data
  // needed to judge it has actually loaded — a page refresh mid-review doesn't lose pending comments,
  // but a draft that no longer applies (the Work Item moved on, the user lost their reviewer seat) is
  // discarded with a toast instead of silently restored, and drafts on a document that changed or
  // disappeared are dropped rather than counted invisibly. Runs once per mount. Mutation call sites
  // elsewhere persist directly rather than watching this state reactively, so there's no race with
  // this one-time read.
  useEffect(() => {
    if (hydratedDraftRef.current) return
    if (loading || !workflowView || !draftKey) return
    hydratedDraftRef.current = true

    const draft = readPersistedJSON<ReviewDraft>(draftKey, EMPTY_REVIEW_DRAFT, isValidReviewDraft)
    if (!draft.active && draft.pending.length === 0) return

    if (!(isAssignedReviewer && reviewActive)) {
      removePersisted(draftKey)
      toastError('Review draft discarded — the work item moved on')
      return
    }

    const { valid, droppedCount } = dropStaleDrafts(draft.pending, documents)
    if (droppedCount > 0) {
      toastError(
        `${droppedCount} draft comment${droppedCount !== 1 ? 's' : ''} discarded — the referenced document changed`
      )
    }
    setReviewMode(draft.active)
    setPendingComments(valid)
    persistReviewDraft(draftKey, draft.active, valid)
  }, [loading, workflowView, draftKey, isAssignedReviewer, reviewActive, documents])

  async function handleUnassignReviewer(userId: string) {
    if (!accessToken) return
    try {
      await apiDelete(
        `/api/v2/projects/${projectId}/work-items/${issueId}/reviewers/${userId}`,
        accessToken
      )
      await fetchReviewers()
    } catch (err) {
      toastError(apiErrorMessage(err, 'Failed to remove reviewer'))
    }
  }

  async function handleAssignReviewer(userId: string) {
    if (!accessToken) return
    try {
      await apiPost(
        `/api/v2/projects/${projectId}/work-items/${issueId}/reviewers`,
        { userId },
        accessToken
      )
      await fetchReviewers()
    } catch (err) {
      toastError(apiErrorMessage(err, 'Failed to add reviewer'))
    }
  }

  // ── Batch review mode actions ───────────────────────────────────────────────

  function startReview() {
    setReviewMode(true)
    if (draftKey) persistReviewDraft(draftKey, true, pendingComments)
  }

  function addPendingComment(documentId: string, lineNumber: number, content: string) {
    const next = [...pendingComments, { localId: nextPendingId(), documentId, lineNumber, content }]
    setPendingComments(next)
    if (draftKey) persistReviewDraft(draftKey, reviewMode, next)
  }

  function editPendingComment(localId: string, content: string) {
    const next = pendingComments.map((p) => (p.localId === localId ? { ...p, content } : p))
    setPendingComments(next)
    if (draftKey) persistReviewDraft(draftKey, reviewMode, next)
  }

  function removePendingComment(localId: string) {
    const next = pendingComments.filter((p) => p.localId !== localId)
    setPendingComments(next)
    if (draftKey) persistReviewDraft(draftKey, reviewMode, next)
  }

  function discardReview() {
    setReviewMode(false)
    setPendingComments([])
    if (draftKey) removePersisted(draftKey)
    setCancelConfirmOpen(false)
  }

  function handleCancelReview() {
    if (pendingComments.length > 0) {
      setCancelConfirmOpen(true)
    } else {
      discardReview()
    }
  }

  async function handleSubmitReview(verdict: Verdict, summary: string) {
    if (!accessToken) return
    setReviewSubmitting(true)
    try {
      // Drop drafts whose document changed out from under them before posting anything — a stale
      // draft should never silently count toward (or block) the batch.
      const { valid: toSubmit, droppedCount } = dropStaleDrafts(pendingComments, documents)
      if (droppedCount > 0) {
        setPendingComments(toSubmit)
        if (draftKey) persistReviewDraft(draftKey, true, toSubmit)
        toastError(
          `${droppedCount} draft comment${droppedCount !== 1 ? 's' : ''} discarded — the referenced document changed`
        )
      }

      const results = await Promise.allSettled(
        toSubmit.map((p) =>
          apiPost(
            `/api/v2/projects/${projectId}/work-items/${issueId}/comments`,
            { documentId: p.documentId, content: p.content, lineNumber: p.lineNumber },
            accessToken
          )
        )
      )
      const succeeded = toSubmit.filter((_, i) => results[i].status === 'fulfilled')
      const failed = toSubmit.filter((_, i) => results[i].status === 'rejected')

      // Whatever posted is gone for good — clear it from pending (and the persisted draft) and pull
      // the fresh comments so they show up, regardless of what the verdict POST below does. Otherwise
      // a retry after a failed verdict POST would re-post everything that already succeeded.
      if (succeeded.length > 0) {
        setPendingComments(failed)
        if (draftKey) persistReviewDraft(draftKey, true, failed)
        await fetchComments()
      }

      if (failed.length > 0) {
        toastError(
          `${failed.length} of ${toSubmit.length} comment${toSubmit.length !== 1 ? 's' : ''} failed to post — they're still pending`
        )
        return
      }

      await apiPost(
        `/api/v2/projects/${projectId}/work-items/${issueId}/reviews`,
        { verdict, body: summary || undefined },
        accessToken
      )
      setPendingComments([])
      setReviewMode(false)
      if (draftKey) removePersisted(draftKey)
      toastSuccess('Review submitted')
      await Promise.all([fetchReviewers(), fetchReviews()])
    } catch (err) {
      toastError(apiErrorMessage(err, 'Failed to submit review'))
    } finally {
      setReviewSubmitting(false)
    }
  }

  // ── Command palette integration ──────────────────────────────────────────────
  // On mobile the properties panel (where both triggers live) is hidden unless the Details tab is
  // active — switch to it first so the trigger is actually visible/interactive before opening it. A
  // rAF gives the tab-switch re-render (and the CSS `hidden` → `block` flip) a chance to land before
  // openMenuTrigger dispatches its pointerdown. Desktop already shows the panel regardless of tab, so
  // this is a no-op there beyond the (harmless) tab-state change.
  function openPanelTrigger(ref: React.RefObject<HTMLButtonElement | null>) {
    setActiveTab('details')
    requestAnimationFrame(() => openMenuTrigger(ref.current))
  }

  useEffect(() => {
    if (!issue) return
    // Both triggers live in the properties panel; a REVIEWER only ever sees a read-only status
    // indicator and no AssigneeCell at all (see WorkItemPropertiesPanel), so neither action has a
    // live target to open for that role.
    const canChangeStatusOrAssign = userRole !== 'REVIEWER'
    return registerPaletteActions({
      group: 'Work item',
      actions: [
        ...(canChangeStatusOrAssign
          ? [
              {
                id: 'wi-change-status',
                label: 'Change status',
                perform: () => openPanelTrigger(statusTriggerRef),
              },
              {
                id: 'wi-assign',
                label: 'Assign',
                perform: () => openPanelTrigger(assigneeTriggerRef),
              },
            ]
          : []),
        ...(reviewActive && isAssignedReviewer && !reviewMode
          ? [{ id: 'wi-start-review', label: 'Start review', perform: startReview }]
          : []),
      ],
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [issue, userRole, reviewActive, isAssignedReviewer, reviewMode])

  const creatorName = issue?.createdBy
    ? allMembers.find((m) => m.userId === issue.createdBy)?.name
    : undefined

  if (loading) {
    return <WorkItemDetailSkeleton />
  }

  if (error) {
    return (
      <PageContainer>
        <div className="flex items-center justify-center h-64 text-destructive">Error: {error}</div>
      </PageContainer>
    )
  }

  if (!issue) return null

  // Breadcrumb trail: area (non-link) › pluralized noun (link to the Workflow list) › this Work Item's id.
  const crumbs: Crumb[] = []
  if (workflowView?.area) crumbs.push({ label: humanizeId(workflowView.area) })
  if (workflowView?.noun) {
    crumbs.push({
      label: pluralizeNoun(workflowView.noun),
      href: workItemListPath(projectId, workflowView.area ?? slug, workflowView.noun),
    })
  }
  crumbs.push({ label: issue.displayId ?? issue.title })

  const byline = creatorName
    ? `Created by ${creatorName}${issue.updatedAt ? ` · updated ${timeAgo(issue.updatedAt)}` : ''}`
    : issue.updatedAt
      ? `Updated ${timeAgo(issue.updatedAt)}`
      : undefined

  let mainContent: React.ReactNode
  if (activeTab === 'activity') {
    mainContent = <ActivityTab comments={comments} reviews={reviews} />
  } else if (documents.length === 0) {
    mainContent = (
      <EmptyState
        icon={FileX2}
        title="No documents attached yet"
        description="Work Items are authored by agents — documents will show up here once created."
      />
    )
  } else if (selectedDoc && isMarkdown(selectedDoc) && selectedDoc.content) {
    mainContent = (
      <CommentableDocument
        content={selectedDoc.content}
        documentId={selectedDoc.id}
        issueId={issueId}
        projectId={projectId}
        comments={comments.filter((c) => c.documentId === selectedDoc.id)}
        onCommentAdded={fetchComments}
        token={accessToken!}
        currentUserId={user?.id ?? ''}
        onDocumentNavigate={(filename) => {
          const target = documents.find((d) => d.filename === filename)
          if (target) selectDocument(target)
        }}
        reviewMode={reviewMode}
        pendingComments={pendingComments}
        onAddPendingComment={(lineNumber, text) => addPendingComment(selectedDoc.id, lineNumber, text)}
        onEditPendingComment={editPendingComment}
        onRemovePendingComment={removePendingComment}
      />
    )
  } else if (selectedDoc && isMarkdown(selectedDoc) && !selectedDoc.content) {
    mainContent = <p className="text-sm text-muted-foreground">Document content is empty.</p>
  } else if (selectedDoc && isHtml(selectedDoc) && selectedDoc.content) {
    mainContent = <HtmlViewer content={selectedDoc.content} />
  } else if (selectedDoc && isHtml(selectedDoc) && !selectedDoc.content) {
    mainContent = <p className="text-sm text-muted-foreground">Document content is empty.</p>
  } else {
    mainContent = <p className="text-sm text-muted-foreground">Select a document to view its contents.</p>
  }

  // All tab ids in DOM order (roving tabindex + Arrow key navigation walk this array). 'details' is
  // a mobile-only alias that reveals the properties panel rather than changing the main content, so
  // the main content panel's aria-labelledby tracks the last content-bearing tab instead of it.
  // With no documents there are no document tabs, so Activity used to be the only one on the bar —
  // and clicking it was a dead end: the media, caption and accounts all hide on Activity, and there was
  // nothing left to click to bring them back. A content tab stands in for the documents that are not
  // there yet, so the bar always offers a way back.
  const showContentTab = documents.length === 0
  const tabIds = [
    ...(showContentTab ? [CONTENT_TAB] : []),
    ...documents.map((d) => d.id),
    'activity',
    'details',
  ]
  const itemLevelCommentCount = unresolvedItemLevelCount(comments)
  const contentTabId = activeTab === 'details' || activeTab === '' ? (selectedDocId ?? 'activity') : activeTab

  const headerActions = reviewActive && isAssignedReviewer && (
    reviewMode ? (
      <span className="text-sm text-muted-foreground">Reviewing…</span>
    ) : (
      <Button size="sm" onClick={startReview}>
        Start review
      </Button>
    )
  )

  return (
    <TikTokPublishGateProvider reason={tiktokBlockedReason}>
      <PageContainer>
        <PageHeader
          breadcrumbs={crumbs}
          title={issue.title}
          status={<StatusBadge status={issue.status} {...statusMeta(workflowView, issue.status)} />}
          description={byline}
          actions={headerActions || undefined}
        />

        {/* Document tabs + Activity (+ Details on mobile). TODO: not migrated to the shared <Tabs>
            primitive (src/components/ui/tabs.tsx) — the tab set is dynamic (one per document, plus
            Activity, plus a mobile-only Details tab with its own tabpanel/aria-controls), and the
            "Details" tab reveals a second panel rather than swapping the same one, which the primitive
            doesn't model. Revisit if Tabs grows multi-panel support. */}
        <div
          role="tablist"
          aria-label="Work item content"
          className="flex items-center gap-1 border-b border-border mb-4 overflow-x-auto overflow-y-hidden"
        >
          {showContentTab && (
            <button
              id={tabButtonId(CONTENT_TAB)}
              ref={(el) => {
                if (el) tabButtonRefs.current.set(CONTENT_TAB, el)
                else tabButtonRefs.current.delete(CONTENT_TAB)
              }}
              role="tab"
              aria-selected={activeTab !== 'activity'}
              aria-controls="wi-tabpanel-main"
              tabIndex={activeTab !== 'activity' ? 0 : -1}
              onClick={() => setActiveTab(CONTENT_TAB)}
              onKeyDown={(e) => handleTabKeyDown(e, tabIds, CONTENT_TAB)}
              className={cn(TAB_CLASSES, activeTab !== 'activity' ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground')}
            >
              {workflowView?.noun ?? 'Content'}
            </button>
          )}
          {documents.map((doc) => {
            const binary = !isMarkdown(doc) && !isHtml(doc)
            const active = activeTab === doc.id
            const commentCount = unresolvedCountForDocument(comments, doc.id)
            const countSuffix = commentCount > 0
              ? `, ${commentCount} unresolved comment${commentCount !== 1 ? 's' : ''}`
              : ''
            return (
              <button
                key={doc.id}
                id={tabButtonId(doc.id)}
                ref={(el) => {
                  if (el) tabButtonRefs.current.set(doc.id, el)
                  else tabButtonRefs.current.delete(doc.id)
                }}
                role="tab"
                aria-selected={active}
                aria-controls="wi-tabpanel-main"
                tabIndex={active ? 0 : -1}
                onClick={() => selectDocument(doc)}
                onKeyDown={(e) => handleTabKeyDown(e, tabIds, doc.id)}
                className={cn(TAB_CLASSES, active ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground')}
                title={`${doc.filename}${countSuffix}`}
                aria-label={`${doc.filename}${countSuffix}`}
              >
                <FileText className="h-3.5 w-3.5 shrink-0" />
                <span className="truncate max-w-[10rem]">{doc.filename}</span>
                {binary && <ExternalLink className="h-3 w-3 shrink-0 opacity-60" />}
                {tabCommentCount(commentCount, active)}
              </button>
            )
          })}
          <button
            id={tabButtonId('activity')}
            ref={(el) => {
              if (el) tabButtonRefs.current.set('activity', el)
              else tabButtonRefs.current.delete('activity')
            }}
            role="tab"
            aria-selected={activeTab === 'activity'}
            aria-controls="wi-tabpanel-main"
            tabIndex={activeTab === 'activity' ? 0 : -1}
            onClick={() => setActiveTab('activity')}
            onKeyDown={(e) => handleTabKeyDown(e, tabIds, 'activity')}
            className={cn(TAB_CLASSES, activeTab === 'activity' ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground')}
            title={itemLevelCommentCount > 0 ? `Activity, ${itemLevelCommentCount} unresolved comment${itemLevelCommentCount !== 1 ? 's' : ''}` : undefined}
            aria-label={itemLevelCommentCount > 0 ? `Activity, ${itemLevelCommentCount} unresolved comment${itemLevelCommentCount !== 1 ? 's' : ''}` : undefined}
          >
            Activity
            {tabCommentCount(itemLevelCommentCount, activeTab === 'activity')}
          </button>
          <button
            id={tabButtonId('details')}
            ref={(el) => {
              if (el) tabButtonRefs.current.set('details', el)
              else tabButtonRefs.current.delete('details')
            }}
            role="tab"
            aria-selected={activeTab === 'details'}
            aria-controls="wi-tabpanel-details"
            tabIndex={activeTab === 'details' ? 0 : -1}
            onClick={() => setActiveTab('details')}
            onKeyDown={(e) => handleTabKeyDown(e, tabIds, 'details')}
            className={cn(TAB_CLASSES, 'md:hidden', activeTab === 'details' ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground')}
          >
            Details
          </button>
        </div>

        <div className="flex gap-6 items-start">
          <div
            id="wi-tabpanel-main"
            role="tabpanel"
            aria-labelledby={tabButtonId(contentTabId)}
            className={cn('flex-1 min-w-0', activeTab === 'details' ? 'hidden md:block' : 'block')}
          >
            <div className="max-w-[45rem] mx-auto space-y-6">
              {mainContent}
              {/* The description, which on a publishing Workflow is the caption that actually goes out.
                  Above the media for the same reason the media sits above the accounts: it is the thing
                  being reviewed, in the order someone writes it. */}
              {activeTab !== 'activity' && (
                <WorkItemDescriptionCard
                  projectId={projectId}
                  workItemId={issueId}
                  token={accessToken!}
                  description={issue.description}
                  status={issue.status}
                  workflowView={workflowView}
                  isCaption={workflowDeclaresPublishTargets(workflowView)}
                  canEdit={userRole !== 'REVIEWER'}
                  onSaved={(description) => {
                    setIssue((prev) => (prev ? { ...prev, description } : prev))
                    // A caption edit is a bundle edit, so the server may have reverted the item and
                    // revoked a hand-off. Re-read rather than assume, as the picker and schedule do.
                    void refreshIssueStatus()
                  }}
                />
              )}
              {/* File assets live with the copy they ship alongside, not in the properties rail — a
                  Post's creative is content, not metadata. Offered only where the bound Workflow
                  declares asset types, since the mint validates `type` against exactly that list. */}
              {activeTab !== 'activity' && (workflowView?.assetTypes?.length ?? 0) > 0 && (
                <MediaUploadPanel
                  projectId={projectId}
                  workItemId={issueId}
                  token={accessToken!}
                  status={issue.status}
                  workflowView={workflowView}
                  assets={assets}
                  onUploaded={fetchAssets}
                />
              )}
              {/* Where the Post goes. Sits with the creative for the same reason: the accounts are part
                  of what a reviewer approves, not metadata. Offered only where the bound Workflow's
                  asset types name a publishable platform, so engineering items never see it. */}
              {activeTab !== 'activity' && workflowDeclaresPublishTargets(workflowView) && (
                <PostTargetPicker
                  projectId={projectId}
                  workItemId={issueId}
                  token={accessToken!}
                  status={issue.status}
                  workflowView={workflowView}
                  onChanged={refreshIssueStatus}
                  onTikTokChange={setTikTokTargets}
                  assets={mediaAssets}
                  caption={issue.description ?? null}
                />
              )}
              {/* The consent TikTok's audit requires. Renders nothing unless the Post actually carries
                  a TikTok target, and sits directly under the picker because the accounts it names are
                  the ones just chosen there. */}
              {activeTab !== 'activity' && (
                <TikTokConsentStep
                  targets={tiktokTargets}
                  assets={mediaAssets}
                  projectId={projectId}
                  workItemId={issueId}
                  token={accessToken!}
                  onConsentChange={(given) => setConsentedTo(given ? consentSubject : null)}
                />
              )}
              {/* What came back from each platform. Directly under the picker, because a permalink and
                  the error next to it answer the same question the account list raises — "did this
                  actually go out?" — and a mixed result has to read as "needs attention" rather than
                  as the roll-up status alone. Renders nothing until the Post has targets. */}
              {activeTab !== 'activity' && workflowDeclaresPublishTargets(workflowView) && (
                <PublishOutcomePanel
                  projectId={projectId}
                  workItemId={issueId}
                  token={accessToken!}
                  workflowView={workflowView}
                  onRetried={refreshIssueStatus}
                />
              )}
            </div>
          </div>

          <aside
            id="wi-tabpanel-details"
            role="tabpanel"
            aria-labelledby={tabButtonId('details')}
            className={cn(
              'w-full md:w-64 md:shrink-0 border-border md:border-l md:pl-6',
              activeTab === 'details' ? 'block' : 'hidden md:block'
            )}
          >
            <WorkItemPropertiesPanel
              projectId={projectId}
              issueId={issueId}
              status={issue.status}
              userRole={userRole}
              token={accessToken!}
              workflowSlug={workflowSlug}
              onStatusChanged={(s) => setIssue((prev) => (prev ? { ...prev, status: s } : prev))}
              statusTriggerRef={statusTriggerRef}
              assignee={issue.assignee}
              members={allMembers}
              onAssigneeChanged={(a) => setIssue((prev) => (prev ? { ...prev, assignee: a } : prev))}
              assigneeTriggerRef={assigneeTriggerRef}
              reviewActive={reviewActive}
              reviewers={reviewers}
              reviews={reviews}
              canManage={canManage}
              tags={issue.tags ?? []}
              knownTags={knownTags}
              onTagsChanged={(tags) => setIssue((prev) => (prev ? { ...prev, tags } : prev))}
              scheduledFor={issue.scheduledFor}
              scheduleTimezone={issue.scheduleTimezone}
              onScheduleChanged={(scheduledFor, scheduleTimezone) => {
                setIssue((prev) => (prev ? { ...prev, scheduledFor, scheduleTimezone } : prev))
                // A schedule edit is a publish-bundle edit, so the server may have reverted the item out
                // of Approved and revoked anything already handed to a platform. Re-read rather than
                // assume, exactly as the target picker does.
                void refreshIssueStatus()
              }}
              assignableReviewers={assignableReviewers}
              onAssignReviewer={handleAssignReviewer}
              onUnassignReviewer={handleUnassignReviewer}
              assets={assets}
            />
          </aside>
        </div>

        {reviewMode && (
          <ReviewBar
            pendingCount={pendingComments.length}
            reviewOutcomes={reviewOutcomes}
            submitting={reviewSubmitting}
            onSubmit={handleSubmitReview}
            onCancel={handleCancelReview}
          />
        )}

        <ConfirmModal
          open={cancelConfirmOpen}
          title="Discard pending comments?"
          description={`You have ${pendingComments.length} unsaved comment${pendingComments.length !== 1 ? 's' : ''} that will be lost.`}
          cancelLabel="Keep reviewing"
          confirmLabel="Discard"
          onConfirm={discardReview}
          onCancel={() => setCancelConfirmOpen(false)}
        >
          <p className="text-sm text-muted-foreground">This can&apos;t be undone.</p>
        </ConfirmModal>
      </PageContainer>
    </TikTokPublishGateProvider>
  )
}
