'use client'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import rehypeSlug from 'rehype-slug'
import { SignedImage } from './SignedImage'
import { MermaidDiagram } from './MermaidDiagram'
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
}

function stripFrontmatter(content: string): string {
  if (!content.startsWith('---')) return content
  const end = content.indexOf('\n---', 3)
  if (end === -1) return content
  return content.slice(end + 4).trimStart()
}

export function MarkdownRenderer({ content, className, onDocumentNavigate, projectId, onWikiLink, basePath }: Props) {
  const stripped = stripFrontmatter(content)
  return (
    <div className={`prose prose-sm dark:prose-invert max-w-none ${className ?? ''}`}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeHighlight, rehypeSlug]}
        components={{
          img: ({ src, alt }) => <SignedImage src={typeof src === 'string' ? src : undefined} alt={alt} />,
          code({ className: cls, children, ...props }) {
            const lang = /language-(\w+)/.exec(cls || '')?.[1]
            if (lang === 'mermaid') {
              return <MermaidDiagram chart={String(children).trim()} />
            }
            return <code className={cls} {...props}>{children}</code>
          },
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
            return <a href={href} {...props}>{children}</a>
          },
        }}
      >
        {stripped}
      </ReactMarkdown>
    </div>
  )
}
