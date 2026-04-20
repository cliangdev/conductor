'use client'

interface HtmlViewerProps {
  content: string
}

export function HtmlViewer({ content }: HtmlViewerProps) {
  return (
    <iframe
      srcDoc={content}
      sandbox="allow-scripts allow-popups"
      className="w-full h-full min-h-[600px] border-0 rounded"
      title="HTML document preview"
    />
  )
}
