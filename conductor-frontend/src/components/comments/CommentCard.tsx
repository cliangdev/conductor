'use client'

import { useState, type ReactNode } from 'react'
import { BotIcon, RotateCcwIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { RowActionsMenu, type RowActionMenuItem } from '@/components/ui/RowActionsMenu'
import { AgentAvatar } from '@/components/agents/AgentAvatar'
import { CommentComposer } from './CommentComposer'
import { renderCommentBody } from './commentBody'
import { useCommentsFeatures } from './CommentsFeatures'
import type {
  Comment,
  CommentAgentRequest,
  CommentAuthorAgent,
  CommentAuthorKind,
  CommentMention,
  CommentReply,
  CommentSuggestion,
  PendingCommentDraft,
} from './types'

/** A body longer than this many lines is folded behind "Show more". */
export const FOLD_LINES = 6
/** Rough characters per line in a card, used to estimate how tall a body is without measuring it. */
const CHARS_PER_LINE = 40
/** More replies than this are folded: only the newest stays visible, behind "Show N replies". */
export const MAX_VISIBLE_REPLIES = 2

/** Whether a body would run past `FOLD_LINES` lines: its own line breaks plus wrapping of long lines. */
export function isLongBody(content: string): boolean {
  let lines = 0
  // Count a mention token as the name it shows, not as its (long) stored form.
  const shown = content.replace(/@\[([^\]]*)\]\((?:user|agent):[^)\s]+\)/g, '@$1')
  for (const line of shown.split('\n')) {
    lines += Math.max(1, Math.ceil(line.length / CHARS_PER_LINE))
    if (lines > FOLD_LINES) return true
  }
  return false
}

function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return iso
  }
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase()
}

/** An agent shows its own avatar; an agent we cannot identify gets a neutral bot; a person gets their initials. */
export function AuthorAvatar({
  kind,
  name,
  agent,
}: {
  kind?: CommentAuthorKind
  name: string
  agent?: CommentAuthorAgent | null
}) {
  if (kind === 'AGENT' && agent) {
    return <AgentAvatar emoji={agent.avatar?.emoji || '🤖'} color={agent.avatar?.color ?? ''} size="md" className="!h-6 !w-6 !text-xs" />
  }
  if (kind === 'AGENT') {
    return (
      <Avatar className="h-6 w-6">
        <AvatarFallback className="bg-muted text-muted-foreground">
          <BotIcon className="h-3.5 w-3.5" aria-hidden />
        </AvatarFallback>
      </Avatar>
    )
  }
  return (
    <Avatar className="h-6 w-6">
      <AvatarFallback className="text-[11px] text-foreground-subtle">{initials(name)}</AvatarFallback>
    </Avatar>
  )
}

/** Plain text, line breaks kept, mentions as chips; long text folds behind "Show more". */
function Body({
  content,
  mentions,
  className,
}: {
  content: string
  mentions?: CommentMention[]
  className?: string
}) {
  const long = isLongBody(content)
  const [expanded, setExpanded] = useState(false)
  const folded = long && !expanded
  return (
    <div className={className}>
      <p className={cn('whitespace-pre-wrap break-words text-sm text-foreground', folded && 'line-clamp-6')}>
        {renderCommentBody(content, mentions)}
      </p>
      {long && (
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation()
            setExpanded((v) => !v)
          }}
          className="mt-0.5 text-xs text-primary hover:underline"
        >
          {expanded ? 'Show less' : 'Show more'}
        </button>
      )}
    </div>
  )
}

/** "<agent> is working on it…" with a pulsing dot, or the failure in plain words; nothing once it succeeded. */
export function AgentRequestStatus({ request }: { request: CommentAgentRequest }) {
  if (request.status === 'PENDING' || request.status === 'RUNNING') {
    return (
      <p
        role="status"
        data-agent-status="working"
        className="mt-2 flex items-center gap-1.5 text-xs text-muted-foreground"
      >
        <span className="relative flex h-2 w-2" aria-hidden>
          <span className="absolute inline-flex h-full w-full rounded-full bg-primary opacity-60 motion-safe:animate-ping" />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-primary" />
        </span>
        {request.agentName} is working on it…
      </p>
    )
  }
  if (request.status === 'FAILED') {
    return (
      <p data-agent-status="failed" className="mt-2 text-xs text-destructive">
        {request.error || `${request.agentName} couldn't finish this request.`}
      </p>
    )
  }
  return null
}

/** A suggested edit: old text struck out, new text inserted; people who can decide get Accept and Reject. */
function SuggestionBlock({
  comment,
  suggestion,
  canDecide,
}: {
  comment: Comment
  suggestion: CommentSuggestion
  canDecide: boolean
}) {
  const features = useCommentsFeatures()
  const [busy, setBusy] = useState<'ACCEPT' | 'REJECT' | null>(null)
  const old = comment.anchor?.exact ?? comment.quotedText ?? ''
  const pending = suggestion.status === 'PENDING'
  const showAccept = !features || features.canAcceptSuggestions

  async function decide(decision: 'ACCEPT' | 'REJECT') {
    if (!features || busy) return
    setBusy(decision)
    try {
      await features.decideSuggestion(comment.id, decision)
    } finally {
      setBusy(null)
    }
  }

  return (
    <div className="mt-1.5" data-suggestion-status={suggestion.status.toLowerCase()}>
      <p className="whitespace-pre-wrap break-words text-[13px]">
        <del className="rounded-sm bg-suggest-del text-foreground">{old}</del>{' '}
        {suggestion.text !== '' && (
          <ins className="rounded-sm bg-suggest-ins text-foreground no-underline">{suggestion.text}</ins>
        )}
      </p>
      {pending && canDecide && features && (
        <div className="mt-2 flex items-center gap-1.5">
          {showAccept && (
            <Button size="sm" disabled={!!busy} onClick={() => void decide('ACCEPT')}>
              {busy === 'ACCEPT' ? 'Accepting…' : 'Accept'}
            </Button>
          )}
          <Button variant="outline" size="sm" disabled={!!busy} onClick={() => void decide('REJECT')}>
            {busy === 'REJECT' ? 'Rejecting…' : 'Reject'}
          </Button>
        </div>
      )}
      {suggestion.status === 'ACCEPTED' && (
        <p className="mt-1.5 text-xs font-medium text-status-approved">
          {suggestion.decidedByName ? `Accepted by ${suggestion.decidedByName}` : 'Accepted'} · thread closed
        </p>
      )}
      {suggestion.status === 'REJECTED' && (
        <p className="mt-1.5 text-xs font-medium text-muted-foreground">
          {suggestion.decidedByName ? `Rejected by ${suggestion.decidedByName}` : 'Rejected'}
        </p>
      )}
    </div>
  )
}

type Confirm = { kind: 'comment' } | { kind: 'reply'; id: string }

export interface CommentCardProps {
  comment: Comment
  /** The focused thread: pinned at its highlight, ringed, and showing its reply box. */
  active?: boolean
  currentUserId: string
  /** Admins may delete anyone's comment. */
  isAdmin?: boolean
  /** Shown above the body: why a comment is under "Whole document" and what it used to be on. */
  note?: ReactNode
  onActivate?: (id: string) => void
  onReply: (id: string, content: string) => Promise<void> | void
  onEdit: (id: string, content: string) => Promise<void> | void
  onEditReply: (id: string, replyId: string, content: string) => Promise<void> | void
  onResolve: (id: string) => Promise<void> | void
  onReopen: (id: string) => Promise<void> | void
  onDelete: (id: string) => Promise<void> | void
  onDeleteReply: (id: string, replyId: string) => Promise<void> | void
  className?: string
}

function authorLabel(c: { authorName?: string | null; authorAgent?: CommentAuthorAgent | null }): string {
  return c.authorName || c.authorAgent?.name || 'Unknown'
}

/** One thread in the rail: the comment, its replies, and what you can do with them. */
export function CommentCard({
  comment,
  active = false,
  currentUserId,
  isAdmin = false,
  note,
  onActivate,
  onReply,
  onEdit,
  onEditReply,
  onResolve,
  onReopen,
  onDelete,
  onDeleteReply,
  className,
}: CommentCardProps) {
  const [editing, setEditing] = useState<'comment' | { reply: string } | null>(null)
  const [confirm, setConfirm] = useState<Confirm | null>(null)
  const [showAllReplies, setShowAllReplies] = useState(false)

  const resolved = comment.status ? comment.status === 'RESOLVED' : !!comment.resolvedAt
  const replies = comment.replies
  const collapsed = replies.length > MAX_VISIBLE_REPLIES && !showAllReplies
  const visibleReplies = collapsed ? replies.slice(-1) : replies

  const isMine = (c: { authorId: string | null; authorKind?: CommentAuthorKind }) =>
    c.authorKind !== 'AGENT' && !!currentUserId && c.authorId === currentUserId
  const canDelete = (c: { authorId: string | null; authorKind?: CommentAuthorKind }) => isMine(c) || isAdmin

  function menuFor(
    c: { authorId: string | null; authorKind?: CommentAuthorKind },
    onEditClick: () => void,
    onDeleteClick: () => void,
    extra: RowActionMenuItem[] = []
  ) {
    const edit = isMine(c) ? onEditClick : undefined
    const del = canDelete(c) ? onDeleteClick : undefined
    if (!edit && !del && extra.length === 0) return null
    return <RowActionsMenu onEdit={edit} onDelete={del} extraItems={extra} />
  }

  function header(
    c: {
      authorName?: string | null
      authorKind?: CommentAuthorKind
      authorAgent?: CommentAuthorAgent | null
      createdAt: string
      editedAt?: string | null
    },
    menu: ReactNode
  ) {
    const name = authorLabel(c)
    return (
      <div className="flex items-center gap-2">
        <AuthorAvatar kind={c.authorKind} name={name} agent={c.authorAgent} />
        <span className="truncate text-[13px] font-semibold text-foreground">{name}</span>
        {c.authorKind === 'AGENT' && (
          <Badge variant="outline" className="px-1.5 py-0 text-[10px] uppercase tracking-wide">
            Agent
          </Badge>
        )}
        <span className="ml-auto flex shrink-0 items-center gap-1.5">
          <span className="whitespace-nowrap text-xs text-foreground-subtle">
            {c.createdAt ? formatTime(c.createdAt) : ''}
          </span>
          {c.editedAt && (
            <span className="text-xs text-foreground-subtle" title={`Edited ${formatTime(c.editedAt)}`}>
              edited
            </span>
          )}
          {menu}
        </span>
      </div>
    )
  }

  const reopenItem: RowActionMenuItem[] = resolved
    ? [{ label: 'Reopen', icon: <RotateCcwIcon className="h-4 w-4" />, onSelect: () => void onReopen(comment.id) }]
    : []

  const confirmText =
    confirm?.kind === 'reply'
      ? (replies.find((r) => r.id === confirm.id)?.content ?? '')
      : comment.content

  function renderReply(r: CommentReply) {
    const isEditing = typeof editing === 'object' && editing !== null && editing.reply === r.id
    return (
      <div key={r.id} className="border-t border-border pt-2" data-reply-id={r.id}>
        {header(
          r,
          menuFor(
            r,
            () => setEditing({ reply: r.id }),
            () => setConfirm({ kind: 'reply', id: r.id })
          )
        )}
        {isEditing ? (
          <div className="mt-1.5">
            <CommentComposer
              autoFocus
              compact
              label="Edit reply"
              initialValue={r.content}
              submitLabel="Save"
              onCancel={() => setEditing(null)}
              onSubmit={async (text) => {
                await onEditReply(comment.id, r.id, text)
                setEditing(null)
              }}
            />
          </div>
        ) : (
          <Body content={r.content} mentions={r.mentions} className="mt-1" />
        )}
      </div>
    )
  }

  return (
    <div
      data-comment-id={comment.id}
      data-active={active ? 'true' : undefined}
      data-state={resolved ? 'resolved' : 'open'}
      onClick={(e) => {
        if ((e.target as HTMLElement).closest('button,textarea,input,a,[role="menuitem"]')) return
        onActivate?.(comment.id)
      }}
      className={cn(
        'rounded-lg border bg-card p-3 text-sm shadow-sm outline-none transition-[box-shadow,border-color]',
        active ? 'border-primary shadow-md' : 'cursor-pointer border-border hover:border-border-strong',
        resolved && 'opacity-90',
        className
      )}
    >
      {note && (
        <div className="mb-2 rounded-md border border-dashed border-border-strong bg-muted/40 px-2 py-1 text-xs text-muted-foreground">
          {note}
        </div>
      )}

      {editing === 'comment' ? (
        <>
          {header({ ...comment }, null)}
          <div className="mt-1.5">
            <CommentComposer
              autoFocus
              compact
              label="Edit comment"
              initialValue={comment.content}
              submitLabel="Save"
              onCancel={() => setEditing(null)}
              onSubmit={async (text) => {
                await onEdit(comment.id, text)
                setEditing(null)
              }}
            />
          </div>
        </>
      ) : (
        <>
          {header(
            comment,
            menuFor(
              comment,
              () => setEditing('comment'),
              () => setConfirm({ kind: 'comment' }),
              reopenItem
            )
          )}
          {comment.suggestion && (
            <Badge variant="outline" className="mt-1.5 px-1.5 py-0 text-[10px] uppercase tracking-wide">
              Suggestion
            </Badge>
          )}
          {comment.suggestion && (
            <SuggestionBlock comment={comment} suggestion={comment.suggestion} canDecide={!!currentUserId} />
          )}
          <Body content={comment.content} mentions={comment.mentions} className="mt-1" />
        </>
      )}

      {resolved && !comment.suggestion?.decidedByName && (
        <p className="mt-1.5 text-xs font-medium text-status-approved">
          {comment.resolvedByName ? `Resolved by ${comment.resolvedByName}` : 'Resolved'}
        </p>
      )}

      {replies.length > 0 && (
        <div className="mt-2 space-y-2">
          {collapsed && (
            <button
              type="button"
              onClick={(e) => {
                e.stopPropagation()
                setShowAllReplies(true)
              }}
              className="text-xs text-primary hover:underline"
            >
              Show {replies.length - 1} {replies.length - 1 === 1 ? 'reply' : 'replies'}
            </button>
          )}
          {visibleReplies.map(renderReply)}
        </div>
      )}

      {(comment.agentRequests ?? []).map((r) => (
        <AgentRequestStatus key={`${r.agentId}:${r.createdAt}`} request={r} />
      ))}

      {active && (
        <div className="mt-2 border-t border-border pt-2">
          {resolved ? (
            <Button variant="ghost" size="sm" onClick={() => void onReopen(comment.id)}>
              <RotateCcwIcon className="mr-1.5 h-3.5 w-3.5" />
              Reopen
            </Button>
          ) : (
            <>
              <CommentComposer
                compact
                label="Reply"
                placeholder="Reply…"
                submitLabel="Reply"
                onSubmit={(text) => onReply(comment.id, text)}
              />
              <div className="mt-2">
                <Button variant="outline" size="sm" onClick={() => void onResolve(comment.id)}>
                  Resolve
                </Button>
              </div>
            </>
          )}
        </div>
      )}

      <ConfirmModal
        open={confirm !== null}
        title={confirm?.kind === 'reply' ? 'Delete reply?' : 'Delete comment?'}
        description={
          confirm?.kind === 'comment' && replies.length > 0
            ? `This also deletes its ${replies.length} ${replies.length === 1 ? 'reply' : 'replies'}. This can't be undone.`
            : "This can't be undone."
        }
        confirmLabel="Delete"
        onCancel={() => setConfirm(null)}
        onConfirm={() => {
          const c = confirm
          setConfirm(null)
          if (!c) return
          if (c.kind === 'comment') void onDelete(comment.id)
          else void onDeleteReply(comment.id, c.id)
        }}
      >
        <p className="text-sm text-muted-foreground">
          {confirmText.length > 120 ? `${confirmText.slice(0, 120)}…` : confirmText}
        </p>
      </ConfirmModal>
    </div>
  )
}

/** A review draft: held in the browser until the review is submitted, so it is marked "Pending" and has no thread. */
export function PendingCommentCard({
  draft,
  active = false,
  editing,
  onActivate,
  onStartEdit,
  onCancelEdit,
  onSave,
  onRemove,
  note,
}: {
  draft: PendingCommentDraft
  active?: boolean
  editing: boolean
  onActivate?: (id: string) => void
  onStartEdit: () => void
  onCancelEdit: () => void
  onSave: (content: string) => void
  onRemove: () => void
  note?: ReactNode
}) {
  return (
    <div
      data-comment-id={`draft:${draft.localId}`}
      data-active={active ? 'true' : undefined}
      onClick={(e) => {
        if ((e.target as HTMLElement).closest('button,textarea,input,a,[role="menuitem"]')) return
        onActivate?.(`draft:${draft.localId}`)
      }}
      className={cn(
        'rounded-lg border bg-card p-3 text-sm shadow-sm',
        active ? 'border-primary shadow-md' : 'cursor-pointer border-border hover:border-border-strong'
      )}
    >
      {note && (
        <div className="mb-2 rounded-md border border-dashed border-border-strong bg-muted/40 px-2 py-1 text-xs text-muted-foreground">
          {note}
        </div>
      )}
      <div className="flex items-center justify-between gap-2">
        <Badge variant="outline" className="text-[10px]">
          Pending
        </Badge>
        <RowActionsMenu onEdit={onStartEdit} onDelete={onRemove} />
      </div>
      {editing ? (
        <div className="mt-1.5">
          <CommentComposer
            autoFocus
            compact
            label="Edit pending comment"
            initialValue={draft.content}
            submitLabel="Save"
            onCancel={onCancelEdit}
            onSubmit={(text) => onSave(text)}
          />
        </div>
      ) : (
        <Body content={draft.content} className="mt-1" />
      )}
    </div>
  )
}
