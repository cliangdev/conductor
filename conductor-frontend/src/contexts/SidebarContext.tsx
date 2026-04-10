'use client'

import { createContext, useContext, useEffect, useState } from 'react'

interface SidebarContextValue {
  isOpen: boolean
  toggleSidebar: () => void
  closeSidebar: () => void
  sidebarWidth: number
  setSidebarWidth: (w: number) => void
}

const SidebarContext = createContext<SidebarContextValue | null>(null)

export function SidebarProvider({ children }: { children: React.ReactNode }) {
  const [isOpen, setIsOpen] = useState(false)
  const [sidebarWidth, setSidebarWidthState] = useState(240)

  useEffect(() => {
    const stored = localStorage.getItem('sidebar_width')
    if (stored) {
      const parsed = parseInt(stored, 10)
      if (parsed >= 160 && parsed <= 400) setSidebarWidthState(parsed)
    }
  }, [])

  function toggleSidebar() {
    setIsOpen((prev) => !prev)
  }

  function closeSidebar() {
    setIsOpen(false)
  }

  function setSidebarWidth(w: number) {
    setSidebarWidthState(w)
  }

  return (
    <SidebarContext.Provider value={{ isOpen, toggleSidebar, closeSidebar, sidebarWidth, setSidebarWidth }}>
      {children}
    </SidebarContext.Provider>
  )
}

export function useSidebar() {
  const ctx = useContext(SidebarContext)
  if (!ctx) throw new Error('useSidebar must be used within SidebarProvider')
  return ctx
}
