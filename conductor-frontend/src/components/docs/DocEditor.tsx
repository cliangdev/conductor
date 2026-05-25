'use client'

import { useEffect, useRef, useState } from 'react'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast'
import { updateDoc } from '@/lib/docs-api'
import type { ProjectDoc } from '@/lib/docs-api'
import type { MemberRole } from '@/types'

export interface DocEditorProps {
  doc: ProjectDoc
  projectId: string
  token: string
  userRole: MemberRole
  onSaved: (updatedDoc: ProjectDoc) => void
  onCancel: () => void
}

function insertAtCursor(
  textarea: HTMLTextAreaElement,
  before: string,
  after: string,
): string {
  const start = textarea.selectionStart
  const end = textarea.selectionEnd
  const value = textarea.value
  const selected = value.slice(start, end)
  const newValue =
    value.slice(0, start) + before + selected + after + value.slice(end)

  // Restore cursor position after state update via a queued microtask
  const newCursorStart = start + before.length
  const newCursorEnd = newCursorStart + selected.length
  requestAnimationFrame(() => {
    textarea.setSelectionRange(newCursorStart, newCursorEnd)
    textarea.focus()
  })

  return newValue
}

function prependLine(textarea: HTMLTextAreaElement, prefix: string): string {
  const start = textarea.selectionStart
  const value = textarea.value
  const lineStart = value.lastIndexOf('\n', start - 1) + 1
  const newValue = value.slice(0, lineStart) + prefix + value.slice(lineStart)

  const newCursor = start + prefix.length
  requestAnimationFrame(() => {
    textarea.setSelectionRange(newCursor, newCursor)
    textarea.focus()
  })

  return newValue
}

export function DocEditor({
  doc,
  projectId,
  token,
  userRole,
  onSaved,
  onCancel,
}: DocEditorProps) {
  const [value, setValue] = useState(doc.content ?? '')
  const [saving, setSaving] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const { showToast } = useToast()

  // REVIEWER guard: redirect immediately on mount
  useEffect(() => {
    if (userRole === 'REVIEWER') {
      onCancel()
    }
  }, [userRole, onCancel])

  if (userRole === 'REVIEWER') return null

  const isDirty = value !== (doc.content ?? '')

  function handleCancel() {
    if (isDirty && !window.confirm('Discard unsaved changes?')) return
    onCancel()
  }

  async function handleSave() {
    setSaving(true)
    try {
      const updated = await updateDoc(projectId, doc.id, value, token)
      onSaved(updated)
    } catch (err) {
      showToast(
        err instanceof Error ? err.message : 'Failed to save document',
        'error',
      )
    } finally {
      setSaving(false)
    }
  }

  function applyWrap(before: string, after: string) {
    if (!textareaRef.current) return
    setValue(insertAtCursor(textareaRef.current, before, after))
  }

  function applyPrepend(prefix: string) {
    if (!textareaRef.current) return
    setValue(prependLine(textareaRef.current, prefix))
  }

  return (
    <div className="flex flex-col h-full">
      {/* Header row */}
      <div className="border-b border-border bg-background px-4 sm:px-6 py-3 shrink-0">
        <div className="flex items-center gap-3">
          <span className="text-sm font-medium text-foreground flex-1 min-w-0 truncate">
            ✏ Editing: {doc.title}
          </span>
          <div className="flex items-center gap-2 shrink-0">
            <Button
              variant="outline"
              size="sm"
              onClick={handleCancel}
              disabled={saving}
            >
              Cancel
            </Button>
            <Button size="sm" onClick={handleSave} disabled={saving}>
              {saving ? 'Saving…' : 'Save'}
            </Button>
          </div>
        </div>
      </div>

      {/* Toolbar */}
      <div className="border-b border-border bg-background px-4 sm:px-6 py-2 shrink-0 flex items-center gap-1 flex-wrap">
        <button
          type="button"
          title="Bold"
          className="h-7 px-2 rounded text-sm font-bold hover:bg-accent hover:text-accent-foreground transition-colors"
          onClick={() => applyWrap('**', '**')}
        >
          B
        </button>
        <button
          type="button"
          title="Italic"
          className="h-7 px-2 rounded text-sm italic hover:bg-accent hover:text-accent-foreground transition-colors"
          onClick={() => applyWrap('_', '_')}
        >
          I
        </button>
        <button
          type="button"
          title="Inline code"
          className="h-7 px-2 rounded text-sm font-mono hover:bg-accent hover:text-accent-foreground transition-colors"
          onClick={() => applyWrap('`', '`')}
        >
          `
        </button>
        <span className="w-px h-4 bg-border mx-0.5" aria-hidden />
        <button
          type="button"
          title="Heading 1"
          className="h-7 px-2 rounded text-sm font-semibold hover:bg-accent hover:text-accent-foreground transition-colors"
          onClick={() => applyPrepend('# ')}
        >
          H1
        </button>
        <button
          type="button"
          title="Heading 2"
          className="h-7 px-2 rounded text-sm font-semibold hover:bg-accent hover:text-accent-foreground transition-colors"
          onClick={() => applyPrepend('## ')}
        >
          H2
        </button>
        <button
          type="button"
          title="Bullet list"
          className="h-7 px-2 rounded text-sm hover:bg-accent hover:text-accent-foreground transition-colors"
          onClick={() => applyPrepend('- ')}
        >
          List
        </button>
        <span className="w-px h-4 bg-border mx-0.5" aria-hidden />
        <button
          type="button"
          title="Image — coming soon"
          disabled
          className="h-7 px-2 rounded text-sm opacity-40 cursor-not-allowed"
        >
          Image
        </button>
      </div>

      {/* Editor panes */}
      <div className="flex flex-1 overflow-hidden">
        {/* Left: textarea */}
        <div className="w-1/2 flex flex-col border-r border-border">
          <div className="px-3 py-1 text-xs text-muted-foreground bg-muted/30 border-b border-border">
            Markdown
          </div>
          <textarea
            ref={textareaRef}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            className="flex-1 resize-none p-4 font-mono text-sm bg-background text-foreground focus:outline-none"
            spellCheck={false}
            placeholder="Write markdown here…"
          />
        </div>

        {/* Right: preview */}
        <div className="w-1/2 flex flex-col overflow-hidden">
          <div className="px-3 py-1 text-xs text-muted-foreground bg-muted/30 border-b border-border">
            Preview
          </div>
          <div className="flex-1 overflow-y-auto p-4">
            <MarkdownRenderer content={value} />
          </div>
        </div>
      </div>
    </div>
  )
}
