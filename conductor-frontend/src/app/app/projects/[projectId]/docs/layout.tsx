export default function DocsLayout({
  children,
}: {
  children: React.ReactNode
  params: { projectId: string }
}) {
  return (
    <div className="flex h-full">
      {/* Left panel: reserved for DocTree (added in T4.2) */}
      <div className="w-60 shrink-0 border-r border-border bg-sidebar-bg overflow-y-auto" />

      {/* Right panel: page content */}
      <div className="flex-1 overflow-y-auto">{children}</div>
    </div>
  )
}
