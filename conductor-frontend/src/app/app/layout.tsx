export const dynamic = 'force-dynamic'

import { ProjectProvider } from '@/contexts/ProjectContext'
import { SidebarProvider } from '@/contexts/SidebarContext'
import { OrgProvider } from '@/contexts/OrgContext'
import { OrgGate } from '@/components/layout/OrgGate'
import { Navbar } from '@/components/layout/Navbar'
import { Sidebar } from '@/components/layout/Sidebar'

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <OrgProvider>
      <OrgGate>
        <ProjectProvider>
          <SidebarProvider>
            <div className="flex h-screen overflow-hidden">
              <Sidebar />
              <div className="flex flex-col flex-1 min-w-0 overflow-hidden">
                <Navbar />
                <main className="flex-1 overflow-y-auto">{children}</main>
              </div>
            </div>
          </SidebarProvider>
        </ProjectProvider>
      </OrgGate>
    </OrgProvider>
  )
}
