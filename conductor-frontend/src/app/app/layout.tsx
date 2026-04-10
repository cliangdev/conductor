export const dynamic = 'force-dynamic'

import { ProjectProvider } from '@/contexts/ProjectContext'
import { Navbar } from '@/components/layout/Navbar'

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <ProjectProvider>
      <div className="min-h-screen flex flex-col">
        <Navbar />
        <main className="flex-1">{children}</main>
      </div>
    </ProjectProvider>
  )
}
