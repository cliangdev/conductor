'use client'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import rehypeHighlight from 'rehype-highlight'
import rehypeSlug from 'rehype-slug'
import { SignedImage } from './SignedImage'
import { MermaidDiagram } from './MermaidDiagram'

interface Props {
  content: string
  className?: string
  onDocumentNavigate?: (filename: string) => void
}

function stripFrontmatter(content: string): string {
  if (!content.startsWith('---')) return content
  const end = content.indexOf('\n---', 3)
  if (end === -1) return content
  return content.slice(end + 4).trimStart()
}

export function MarkdownRenderer({ content, className, onDocumentNavigate }: Props) {
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
