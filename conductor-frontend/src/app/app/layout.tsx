export const dynamic = 'force-dynamic'

import { ProjectProvider } from '@/contexts/ProjectContext'
import { SidebarProvider } from '@/contexts/SidebarContext'
import { EditorChromeProvider } from '@/contexts/EditorChromeContext'
import { Navbar } from '@/components/layout/Navbar'
import { Sidebar } from '@/components/layout/Sidebar'
import { CommandPalette } from '@/components/layout/CommandPalette'

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <ProjectProvider>
      <SidebarProvider>
        <EditorChromeProvider>
          <div className="flex h-screen overflow-hidden">
            <Sidebar />
            <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
              <Navbar />
              <main className="flex-1 overflow-y-auto">{children}</main>
            </div>
          </div>
          <CommandPalette />
        </EditorChromeProvider>
      </SidebarProvider>
    </ProjectProvider>
  )
}
