'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Bold,
  Code,
  ChevronDown,
  Heading,
  Image as ImageIcon,
  Italic,
  Link,
  List,
  ListChecks,
  ListOrdered,
  Maximize2,
  Minimize2,
  MoreHorizontal,
  PanelRightClose,
  PanelRightOpen,
  Quote,
  Redo2,
  Strikethrough,
  Table,
  Undo2,
} from 'lucide-react'
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useToast } from '@/components/ui/toast'
import { useEditorChrome } from '@/contexts/EditorChromeContext'
import { ResizableSplit } from '@/components/ui/ResizableSplit'
import { updateDoc } from '@/lib/docs-api'
import type { ProjectDoc } from '@/lib/docs-api'
import type { MemberRole } from '@/types'

const ALLOWED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']

export interface DocEditorProps {
  doc: ProjectDoc
  projectId: string
  token: string
  userRole: MemberRole
  onSaved: (updatedDoc: ProjectDoc) => void
  onCancel: () => void
}

// ─── Text manipulation helpers ─────────────────────────────────────────────────

function insertAtCursor(
  ta: HTMLTextAreaElement,
  before: string,
  after: string,
  selectPlaceholder?: [number, number], // [start, end] relative to `before`
): string {
  const start = ta.selectionStart
  const end = ta.selectionEnd
  const val = ta.value
  const selected = val.slice(start, end)
  const next = val.slice(0, start) + before + selected + after + val.slice(end)
  requestAnimationFrame(() => {
    if (selectPlaceholder) {
      ta.setSelectionRange(start + selectPlaceholder[0], start + selectPlaceholder[1])
    } else {
      ta.setSelectionRange(start + before.length, start + before.length + selected.length)
    }
    ta.focus()
  })
  return next
}

function prependLine(ta: HTMLTextAreaElement, prefix: string): string {
  const start = ta.selectionStart
  const val = ta.value
  const lineStart = val.lastIndexOf('\n', start - 1) + 1
  const next = val.slice(0, lineStart) + prefix + val.slice(lineStart)
  const newCursor = start + prefix.length
  requestAnimationFrame(() => { ta.setSelectionRange(newCursor, newCursor); ta.focus() })
  return next
}

function insertBlock(ta: HTMLTextAreaElement, block: string): string {
  const pos = ta.selectionStart
  const val = ta.value
  const needsNewline = pos > 0 && val[pos - 1] !== '\n'
  const prefix = needsNewline ? '\n' : ''
  const next = val.slice(0, pos) + prefix + block + '\n' + val.slice(pos)
  const newCursor = pos + prefix.length + block.length + 1
  requestAnimationFrame(() => { ta.setSelectionRange(newCursor, newCursor); ta.focus() })
  return next
}

function readBoolPref(key: string, fallback: boolean): boolean {
  if (typeof window === 'undefined') return fallback
  const v = localStorage.getItem(key)
  return v === null ? fallback : v === 'true'
}

// ─── Toolbar primitives ────────────────────────────────────────────────────────

function Sep() {
  return <span className="w-px h-5 bg-border mx-1 shrink-0" aria-hidden />
}

interface TbBtnProps {
  icon: React.ElementType
  title: string
  onClick: () => void
  disabled?: boolean
  active?: boolean
}

function TbBtn({ icon: Icon, title, onClick, disabled, active }: TbBtnProps) {
  return (
    <button
      type="button"
      title={title}
      onClick={onClick}
      disabled={disabled}
      className={`h-8 w-8 flex items-center justify-center rounded transition-colors shrink-0
        ${active ? 'bg-accent text-accent-foreground' : 'text-foreground hover:bg-accent hover:text-accent-foreground'}
        disabled:opacity-40 disabled:cursor-not-allowed`}
    >
      <Icon className="h-4 w-4" />
    </button>
  )
}

// ─── Component ─────────────────────────────────────────────────────────────────

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
  const [uploading, setUploading] = useState(false)

  const [previewCollapsed, setPreviewCollapsedState] = useState(() =>
    readBoolPref('doc_editor_preview_collapsed', false),
  )
  const [toolbarCollapsed, setToolbarCollapsedState] = useState(() =>
    readBoolPref('doc_editor_toolbar_collapsed', false),
  )

  // Undo/redo history
  const historyRef = useRef<string[]>([doc.content ?? ''])
  const historyIdxRef = useRef(0)
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [canUndo, setCanUndo] = useState(false)
  const [canRedo, setCanRedo] = useState(false)

  function pushHistory(v: string) {
    const idx = historyIdxRef.current
    const hist = historyRef.current.slice(0, idx + 1)
    if (hist[hist.length - 1] === v) return
    hist.push(v)
    historyRef.current = hist
    historyIdxRef.current = hist.length - 1
    setCanUndo(hist.length > 1)
    setCanRedo(false)
  }

  function applyAndRecord(next: string) {
    setValue(next)
    pushHistory(next)
  }

  function undo() {
    if (historyIdxRef.current <= 0) return
    historyIdxRef.current--
    const v = historyRef.current[historyIdxRef.current]
    setValue(v)
    setCanUndo(historyIdxRef.current > 0)
    setCanRedo(true)
  }

  function redo() {
    if (historyIdxRef.current >= historyRef.current.length - 1) return
    historyIdxRef.current++
    const v = historyRef.current[historyIdxRef.current]
    setValue(v)
    setCanUndo(true)
    setCanRedo(historyIdxRef.current < historyRef.current.length - 1)
  }

  function handleChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    const v = e.target.value
    setValue(v)
    if (debounceRef.current) clearTimeout(debounceRef.current)
    debounceRef.current = setTimeout(() => pushHistory(v), 500)
  }

  const { fullscreen, setFullscreen } = useEditorChrome()
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const { showToast } = useToast()

  function setPreviewCollapsed(v: boolean) {
    setPreviewCollapsedState(v)
    localStorage.setItem('doc_editor_preview_collapsed', String(v))
  }
  function setToolbarCollapsed(v: boolean) {
    setToolbarCollapsedState(v)
    localStorage.setItem('doc_editor_toolbar_collapsed', String(v))
  }

  // ── Toolbar actions ──────────────────────────────────────────────────────────

  function applyWrap(before: string, after: string) {
    if (!textareaRef.current) return
    applyAndRecord(insertAtCursor(textareaRef.current, before, after))
  }

  function applyPrepend(prefix: string) {
    if (!textareaRef.current) return
    applyAndRecord(prependLine(textareaRef.current, prefix))
  }

  function applyCodeBlock() {
    if (!textareaRef.current) return
    const ta = textareaRef.current
    const selected = ta.value.slice(ta.selectionStart, ta.selectionEnd)
    if (selected.includes('\n') || selected === '') {
      applyAndRecord(insertBlock(ta, '```\n' + (selected || '') + '\n```'))
    } else {
      applyAndRecord(insertAtCursor(ta, '`', '`'))
    }
  }

  function applyLink() {
    if (!textareaRef.current) return
    const ta = textareaRef.current
    const selected = ta.value.slice(ta.selectionStart, ta.selectionEnd)
    const before = selected ? `[${selected}](` : '[link text]('
    const placeholderStart = before.indexOf('(') + 1
    applyAndRecord(insertAtCursor(ta, before, ')', [placeholderStart, before.length]))
  }

  function applyTable() {
    if (!textareaRef.current) return
    applyAndRecord(insertBlock(textareaRef.current,
      '| Column 1 | Column 2 | Column 3 |\n| -------- | -------- | -------- |\n|          |          |          |',
    ))
  }

  // ── Keyboard shortcuts ───────────────────────────────────────────────────────

  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      const mod = e.metaKey || e.ctrlKey
      const inEditor = document.activeElement === textareaRef.current

      if (inEditor && mod) {
        if (e.key === 'z' && !e.shiftKey) { e.preventDefault(); undo(); return }
        if ((e.key === 'y') || (e.key === 'z' && e.shiftKey)) { e.preventDefault(); redo(); return }
        if (!e.shiftKey) {
          if (e.key === 'b') { e.preventDefault(); applyWrap('**', '**'); return }
          if (e.key === 'i') { e.preventDefault(); applyWrap('_', '_'); return }
          if (e.key === 'k') { e.preventDefault(); applyLink(); return }
          if (e.key === '`') { e.preventDefault(); applyCodeBlock(); return }
        }
        if (e.shiftKey) {
          if (e.key === 'X' || e.key === 'x') { e.preventDefault(); applyWrap('~~', '~~'); return }
          if (e.key === '7') { e.preventDefault(); applyPrepend('1. '); return }
          if (e.key === '8') { e.preventDefault(); applyPrepend('- '); return }
          if (e.key === '9') { e.preventDefault(); applyPrepend('> '); return }
        }
        return
      }

      if (e.key === 'Escape' && fullscreen) { setFullscreen(false); return }
      if (e.key === 'F11') { e.preventDefault(); setFullscreen(!fullscreen); return }
      if (mod && e.shiftKey && (e.key === 'f' || e.key === 'F')) { e.preventDefault(); setFullscreen(!fullscreen) }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [fullscreen, setFullscreen, canUndo, canRedo],
  )

  useEffect(() => {
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [handleKeyDown])

  // ── Image upload ─────────────────────────────────────────────────────────────

  async function uploadImage(file: File) {
    if (!ALLOWED_IMAGE_TYPES.includes(file.type)) {
      showToast('Invalid file type. Allowed: PNG, JPEG, GIF, WebP', 'error')
      return
    }
    setUploading(true)
    try {
      const formData = new FormData()
      formData.append('image', file)
      const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? ''
      const res = await fetch(
        `${apiUrl}/api/v1/projects/${projectId}/docs/${doc.id}/images`,
        { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: formData },
      )
      if (!res.ok) throw new Error((await res.text()) || `Upload failed (${res.status})`)
      const data = (await res.json()) as { markdownSnippet: string }
      // Inject a default width: ![alt](url) → ![alt|400](url)
      const snippet = data.markdownSnippet.replace(/^!\[([^\]]*)\]/, '![$1|400]')
      if (textareaRef.current) {
        applyAndRecord(insertAtCursor(textareaRef.current, snippet, ''))
      }
    } catch (err) {
      showToast(err instanceof Error ? err.message : 'Image upload failed', 'error')
    } finally {
      setUploading(false)
    }
  }

  useEffect(() => {
    if (userRole === 'REVIEWER') onCancel()
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
      showToast(err instanceof Error ? err.message : 'Failed to save document', 'error')
    } finally {
      setSaving(false)
    }
  }

  // ── Toolbar contents ─────────────────────────────────────────────────────────

  const toolbarItems = (
    <>
      {/* Undo / Redo */}
      <TbBtn icon={Undo2} title="Undo (⌘Z)" onClick={undo} disabled={!canUndo} />
      <TbBtn icon={Redo2} title="Redo (⌘Y)" onClick={redo} disabled={!canRedo} />
      <Sep />

      {/* Inline formatting */}
      <TbBtn icon={Bold} title="Bold (⌘B)" onClick={() => applyWrap('**', '**')} />
      <TbBtn icon={Italic} title="Italic (⌘I)" onClick={() => applyWrap('_', '_')} />

      {/* Heading dropdown */}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            title="Heading"
            className="h-8 px-1.5 flex items-center gap-0.5 rounded text-foreground hover:bg-accent hover:text-accent-foreground transition-colors shrink-0"
          >
            <Heading className="h-4 w-4" />
            <ChevronDown className="h-3 w-3 opacity-60" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="start">
          <DropdownMenuItem className="cursor-pointer text-2xl font-bold leading-tight" onClick={() => applyPrepend('# ')}>Heading 1</DropdownMenuItem>
          <DropdownMenuItem className="cursor-pointer text-xl font-bold leading-tight" onClick={() => applyPrepend('## ')}>Heading 2</DropdownMenuItem>
          <DropdownMenuItem className="cursor-pointer text-lg font-bold leading-tight" onClick={() => applyPrepend('### ')}>Heading 3</DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

      <TbBtn icon={Strikethrough} title="Strikethrough (⌘⇧X)" onClick={() => applyWrap('~~', '~~')} />
      <Sep />

      {/* Lists */}
      <TbBtn icon={List} title="Bullet list (⌘⇧8)" onClick={() => applyPrepend('- ')} />
      <TbBtn icon={ListOrdered} title="Numbered list (⌘⇧7)" onClick={() => applyPrepend('1. ')} />
      <TbBtn icon={ListChecks} title="Task list" onClick={() => applyPrepend('- [ ] ')} />
      <Sep />

      {/* Block elements */}
      <TbBtn icon={Quote} title="Blockquote (⌘⇧9)" onClick={() => applyPrepend('> ')} />
      <TbBtn icon={Code} title="Code block (⌘`)" onClick={applyCodeBlock} />
      <TbBtn icon={Table} title="Insert table" onClick={applyTable} />
      <Sep />

      {/* Insert */}
      <TbBtn icon={Link} title="Link (⌘K)" onClick={applyLink} />
      <button
        type="button"
        title="Upload image"
        disabled={uploading}
        onClick={() => fileInputRef.current?.click()}
        className="h-8 w-8 flex items-center justify-center rounded text-foreground hover:bg-accent hover:text-accent-foreground transition-colors shrink-0 disabled:opacity-40 disabled:cursor-not-allowed"
      >
        <ImageIcon className="h-4 w-4" />
      </button>
      <input
        ref={fileInputRef}
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) uploadImage(file)
          e.target.value = ''
        }}
      />
    </>
  )

  const collapsedToolbarItems = (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          title="Formatting tools"
          className="h-8 w-8 flex items-center justify-center rounded text-foreground hover:bg-accent hover:text-accent-foreground transition-colors"
        >
          <MoreHorizontal className="h-4 w-4" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-44">
        <DropdownMenuItem className="cursor-pointer" onClick={undo} disabled={!canUndo}>Undo (⌘Z)</DropdownMenuItem>
        <DropdownMenuItem className="cursor-pointer" onClick={redo} disabled={!canRedo}>Redo (⌘Y)</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem className="cursor-pointer font-bold" onClick={() => applyWrap('**', '**')}>Bold (⌘B)</DropdownMenuItem>
        <DropdownMenuItem className="cursor-pointer italic" onClick={() => applyWrap('_', '_')}>Italic (⌘I)</DropdownMenuItem>
        <DropdownMenuItem className="cursor-pointer line-through" onClick={() => applyWrap('~~', '~~')}>Strikethrough</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem className="cursor-pointer" onClick={() => applyPrepend('# ')}>Heading 1</DropdownMenuItem>
        <DropdownMenuItem className="cursor-pointer" onClick={() => applyPrepend('## ')}>Heading 2</DropdownMenuItem>
        <DropdownMenuItem className="cursor-pointer" onClick={() => applyPrepend('### ')}>Heading 3</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem className="cursor-pointer" onClick={() => applyPrepend('- ')}>Bullet list</DropdownMenuItem>
        <DropdownMenuItem className="cursor-pointer" onClick={() => applyPrepend('1. ')}>Numbered list</DropdownMenuItem>
        <DropdownMenuItem className="cursor-pointer" onClick={() => applyPrepend('- [ ] ')}>Task list</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem className="cursor-pointer" onClick={() => applyPrepend('> ')}>Blockquote</DropdownMenuItem>
        <DropdownMenuItem className="cursor-pointer" onClick={applyCodeBlock}>Code block</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem className="cursor-pointer" onClick={applyLink}>Link (⌘K)</DropdownMenuItem>
        <DropdownMenuItem className="cursor-pointer" onClick={applyTable}>Table</DropdownMenuItem>
        <DropdownMenuItem className="cursor-pointer" onClick={() => fileInputRef.current?.click()}>
          {uploading ? 'Uploading…' : 'Image…'}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )

  // ── Render ───────────────────────────────────────────────────────────────────

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="border-b border-border bg-background px-4 sm:px-6 py-3 shrink-0">
        <div className="flex items-center gap-3">
          <span className="text-sm font-medium text-foreground flex-1 min-w-0 truncate">
            ✏ Editing: {doc.title}
          </span>
          <div className="flex items-center gap-2 shrink-0">
            <Button variant="outline" size="sm" onClick={handleCancel} disabled={saving}>Cancel</Button>
            <Button size="sm" onClick={handleSave} disabled={saving}>{saving ? 'Saving…' : 'Save'}</Button>
          </div>
        </div>
      </div>

      {/* Toolbar */}
      <div className="border-b border-border bg-background px-3 py-1 shrink-0 flex items-center gap-0 overflow-x-auto">
        {toolbarCollapsed ? collapsedToolbarItems : toolbarItems}

        {/* Chrome controls pinned right */}
        <div className="ml-auto flex items-center gap-0 shrink-0 pl-2 border-l border-border ml-2">
          <TbBtn
            icon={fullscreen ? Minimize2 : Maximize2}
            title={fullscreen ? 'Exit fullscreen (Esc)' : 'Fullscreen (F11)'}
            onClick={() => setFullscreen(!fullscreen)}
          />
          <TbBtn
            icon={MoreHorizontal}
            title={toolbarCollapsed ? 'Expand toolbar' : 'Collapse toolbar'}
            onClick={() => setToolbarCollapsed(!toolbarCollapsed)}
          />
          <TbBtn
            icon={previewCollapsed ? PanelRightOpen : PanelRightClose}
            title={previewCollapsed ? 'Show preview' : 'Hide preview'}
            onClick={() => setPreviewCollapsed(!previewCollapsed)}
          />
        </div>
      </div>

      {/* Editor panes */}
      <ResizableSplit
        storageKey="doc_editor_pane_split"
        defaultFraction={0.5}
        min={240}
        rightCollapsed={previewCollapsed}
        left={
          <>
            <div className="px-3 py-1 text-xs text-muted-foreground bg-muted/30 border-b border-border shrink-0">
              Markdown
            </div>
            <textarea
              ref={textareaRef}
              value={value}
              onChange={handleChange}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                e.preventDefault()
                const file = e.dataTransfer.files[0]
                if (file && ALLOWED_IMAGE_TYPES.includes(file.type)) uploadImage(file)
              }}
              className="flex-1 resize-none p-4 font-mono text-sm bg-background text-foreground focus:outline-none"
              spellCheck={false}
              placeholder="Write markdown here…"
            />
          </>
        }
        right={
          <>
            <div className="px-3 py-1 text-xs text-muted-foreground bg-muted/30 border-b border-border shrink-0">
              Preview
            </div>
            <div className="flex-1 overflow-y-auto p-4">
              <MarkdownRenderer content={value} />
            </div>
          </>
        }
      />
    </div>
  )
}
