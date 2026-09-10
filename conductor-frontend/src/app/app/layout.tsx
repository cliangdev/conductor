export const dynamic = 'force-dynamic'

import { ProjectProvider } from '@/contexts/ProjectContext'
import { SidebarProvider } from '@/contexts/SidebarContext'
import { EditorChromeProvider } from '@/contexts/EditorChromeContext'
import { Navbar } from '@/components/layout/Navbar'
import { Sidebar } from '@/components/layout/Sidebar'
import { CommandPalette } from '@/components/layout/CommandPalette'
import { AppPermissionsProvider } from '@/components/layout/AppPermissionsProvider'

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <ProjectProvider>
      <SidebarProvider>
        <EditorChromeProvider>
          <AppPermissionsProvider>
            {/* Pinned to the viewport rather than sized to it: an in-flow 100vh shell can still be
                scrolled out of view once anything makes the document taller than the window (a
                focus() into hidden overflow, a portal left in flow), and keeps its old height after
                a video leaves fullscreen. The main pane owns scrolling and does not chain past its
                own end. */}
            <div className="fixed inset-0 flex overflow-hidden">
              <Sidebar />
              <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
                <Navbar />
                <main className="flex-1 overflow-y-auto overscroll-contain">{children}</main>
              </div>
            </div>
            <CommandPalette />
          </AppPermissionsProvider>
        </EditorChromeProvider>
      </SidebarProvider>
    </ProjectProvider>
  )
}
