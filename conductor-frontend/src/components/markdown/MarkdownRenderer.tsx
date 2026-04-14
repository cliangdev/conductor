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
}

export function MarkdownRenderer({ content, className }: Props) {
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
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
