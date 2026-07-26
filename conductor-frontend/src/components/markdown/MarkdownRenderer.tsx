'use client'
import { useMemo } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import rehypeSlug from 'rehype-slug'
import { Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { SignedImage } from './SignedImage'
import { MermaidDiagram } from './MermaidDiagram'
import { rehypeSourceLines } from './rehypeSourceLines'
import { rehypeTaskListLines } from './rehypeTaskListLines'
import { resolveBundleLink } from './resolveBundleLink'

interface Props {
  content: string
  className?: string
  onDocumentNavigate?: (filename: string) => void
  projectId?: string
  /**
   * When set, intercepts bundle-relative markdown links (e.g. "/engineering/architecture.md",
   * "../people/jane.md") and calls this instead of a raw navigation. `basePath` is the directory of
   * the page currently being rendered, used to resolve relative hrefs. Links that aren't
   * bundle-relative ".md" targets (external URLs, mailto:, doc:, fragments) fall through unchanged.
   */
  onWikiLink?: (path: string) => void
  basePath?: string
  /**
   * Opt in to interactive GFM checkboxes. `lineNumber` is 1-based against the `content` string as
   * passed in (frontmatter included — see `stripFrontmatter`), so callers can hand it straight to a
   * line-addressed API. Omit this and checkboxes stay disabled, exactly as markdown renders them by
   * default — which is what every read-only surface (Knowledge, doc history, connector docs, the
   * editor preview) wants.
   */
  onToggleTask?: (lineNumber: number, checked: boolean) => void
  /** Renders checkboxes visible but non-interactive — for readers without edit rights. */
  tasksReadOnly?: boolean
  /**
   * Stamps `data-line-start` / `data-line-end` on rendered blocks so a caller can align gutter
   * furniture with the rendered output instead of guessing at a uniform line height. Off by default —
   * it only adds attributes, but there's no reason to pay for them on surfaces that don't measure.
   */
  annotateSourceLines?: boolean
}

/**
 * Drops the leading frontmatter block from what gets rendered.
 *
 * It is replaced with an equal number of blank lines rather than removed outright, so line numbers in
 * the parsed tree keep matching line numbers in the original string. Leading blank lines are inert in
 * CommonMark, so the rendered output is unchanged — but `onToggleTask` and the comment gutter (which
 * numbers lines against the raw content) then agree without threading an offset through every caller.
 */
function stripFrontmatter(content: string): string {
  if (!content.startsWith('---')) return content
  const end = content.indexOf('\n---', 3)
  if (end === -1) return content
  const body = content.slice(end + 4).trimStart()
  const removed = content.length - body.length
  return '\n'.repeat(countNewlines(content.slice(0, removed))) + body
}

function countNewlines(text: string): number {
  let count = 0
  for (const char of text) if (char === '\n') count += 1
  return count
}

// Hoisted so they aren't re-created (and their subtrees remounted) on every render. One frozen array
// per combination of the two opt-in plugins, rather than building a fresh array each time.
const REHYPE_PLUGINS = {
  base: [rehypeHighlight, rehypeSlug],
  tasks: [rehypeHighlight, rehypeSlug, rehypeTaskListLines],
  lines: [rehypeHighlight, rehypeSlug, rehypeSourceLines],
  both: [rehypeHighlight, rehypeSlug, rehypeTaskListLines, rehypeSourceLines],
}
const REMARK_PLUGINS = [remarkGfm]

function rehypePluginsFor(withTasks: boolean, withSourceLines: boolean) {
  if (withTasks && withSourceLines) return REHYPE_PLUGINS.both
  if (withTasks) return REHYPE_PLUGINS.tasks
  if (withSourceLines) return REHYPE_PLUGINS.lines
  return REHYPE_PLUGINS.base
}

const IMG_COMPONENT: Components['img'] = ({ src, alt }) => (
  <SignedImage src={typeof src === 'string' ? src : undefined} alt={alt} />
)

const CODE_COMPONENT: Components['code'] = ({ className, children, ...props }) => {
  const lang = /language-(\w+)/.exec(className || '')?.[1]
  if (lang === 'mermaid') {
    return <MermaidDiagram chart={String(children).trim()} />
  }
  return (
    <code className={className} {...props}>
      {children}
    </code>
  )
}

/**
 * The interactive replacement for markdown's default `<input type="checkbox" disabled>`.
 *
 * A button rather than a real checkbox input, matching the `Switch` primitive: react-markdown owns the
 * `checked` attribute, so a controlled input here would fight it on every re-render. The source line
 * is read off the hast node (stamped by `rehypeTaskListLines`) rather than a JSX prop, since
 * `JSX.IntrinsicElements['input']` has no index signature for `data-*`.
 */
function makeTaskCheckbox(
  onToggleTask: (lineNumber: number, checked: boolean) => void,
  readOnly: boolean
): Components['input'] {
  return function TaskCheckbox({ node, type, checked, ...props }) {
    const rawLine = node?.properties?.['data-line']
    const lineNumber = typeof rawLine === 'string' ? Number.parseInt(rawLine, 10) : NaN

    // Not a task checkbox, or the plugin couldn't trace it to a line — fall back to markdown's default.
    if (type !== 'checkbox' || !Number.isInteger(lineNumber)) {
      return <input {...props} type={type} checked={checked} disabled readOnly />
    }

    const isChecked = Boolean(checked)

    return (
      <button
        type="button"
        role="checkbox"
        aria-checked={isChecked}
        aria-disabled={readOnly || undefined}
        disabled={readOnly}
        onClick={readOnly ? undefined : () => onToggleTask(lineNumber, !isChecked)}
        className={cn(
          // -mt-px + align-middle sits the box on the text baseline the way the native input did.
          'relative top-[-1px] mr-2 inline-flex h-[15px] w-[15px] shrink-0 select-none items-center',
          'justify-center rounded-[4px] border align-middle transition-colors',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1',
          'focus-visible:ring-offset-background',
          isChecked ? 'border-primary bg-primary' : 'border-border-strong bg-surface',
          readOnly ? 'cursor-default opacity-70' : 'cursor-pointer hover:border-primary'
        )}
      >
        {isChecked && <Check className="h-3 w-3 text-primary-foreground" strokeWidth={3} />}
      </button>
    )
  }
}

export function MarkdownRenderer({
  content,
  className,
  onDocumentNavigate,
  projectId,
  onWikiLink,
  basePath,
  onToggleTask,
  tasksReadOnly = false,
  annotateSourceLines = false,
}: Props) {
  const stripped = stripFrontmatter(content)

  const components = useMemo<Components>(() => {
    const map: Components = {
      img: IMG_COMPONENT,
      code: CODE_COMPONENT,
      a: ({ href, children, ...props }) => {
        if (href?.startsWith('doc:') && projectId) {
          const docId = href.slice(4)
          return (
            <a href={`/app/projects/${projectId}/docs/${docId}`} {...props}>
              {children}
            </a>
          )
        }
        if (onWikiLink) {
          const resolved = resolveBundleLink(href, basePath)
          if (resolved) {
            return (
              <a
                href="#"
                onClick={(e) => {
                  e.preventDefault()
                  onWikiLink(resolved)
                }}
                {...props}
              >
                {children}
              </a>
            )
          }
        }
        if (href?.startsWith('./') && onDocumentNavigate) {
          const filename = href.slice(2)
          return (
            <a
              href="#"
              onClick={(e) => {
                e.preventDefault()
                onDocumentNavigate(filename)
              }}
              {...props}
            >
              {children}
            </a>
          )
        }
        return (
          <a href={href} {...props}>
            {children}
          </a>
        )
      },
    }

    if (onToggleTask) map.input = makeTaskCheckbox(onToggleTask, tasksReadOnly)

    return map
  }, [projectId, onWikiLink, basePath, onDocumentNavigate, onToggleTask, tasksReadOnly])

  return (
    <div className={`prose prose-sm dark:prose-invert max-w-none ${className ?? ''}`}>
      <ReactMarkdown
        remarkPlugins={REMARK_PLUGINS}
        rehypePlugins={rehypePluginsFor(Boolean(onToggleTask), annotateSourceLines)}
        components={components}
      >
        {stripped}
      </ReactMarkdown>
    </div>
  )
}
