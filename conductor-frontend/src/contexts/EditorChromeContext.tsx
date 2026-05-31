'use client'

import { createContext, useContext, useEffect, useState, ReactNode } from 'react'
import { usePathname } from 'next/navigation'

interface EditorChromeContextValue {
  fullscreen: boolean
  setFullscreen: (v: boolean) => void
  docsTreeCollapsed: boolean
  setDocsTreeCollapsed: (v: boolean) => void
}

const EditorChromeContext = createContext<EditorChromeContextValue | null>(null)

export function useEditorChrome(): EditorChromeContextValue {
  const ctx = useContext(EditorChromeContext)
  if (!ctx) throw new Error('useEditorChrome must be used within EditorChromeProvider')
  return ctx
}

export function EditorChromeProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname()

  const [fullscreen, setFullscreen] = useState(false)
  const [docsTreeCollapsed, setDocsTreeCollapsedState] = useState(() => {
    if (typeof window === 'undefined') return false
    return localStorage.getItem('docs_tree_collapsed') === 'true'
  })

  // Exit fullscreen whenever the user navigates away from an /edit page
  useEffect(() => {
    if (!pathname.endsWith('/edit')) {
      setFullscreen(false)
    }
  }, [pathname])

  function setDocsTreeCollapsed(v: boolean) {
    setDocsTreeCollapsedState(v)
    localStorage.setItem('docs_tree_collapsed', String(v))
  }

  return (
    <EditorChromeContext.Provider
      value={{ fullscreen, setFullscreen, docsTreeCollapsed, setDocsTreeCollapsed }}
    >
      {children}
    </EditorChromeContext.Provider>
  )
}
