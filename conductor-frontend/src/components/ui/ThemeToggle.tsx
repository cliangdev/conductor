'use client'

import { useTheme } from 'next-themes'
import { SunIcon, MoonIcon, MonitorIcon } from 'lucide-react'
import { Button } from './button'
import { useEffect, useState } from 'react'

export function ThemeToggle() {
  const { theme, setTheme } = useTheme()
  const [mounted, setMounted] = useState(false)

  useEffect(() => setMounted(true), [])

  if (!mounted) return <div className="w-9 h-9" />

  const cycle = () => {
    if (theme === 'light') setTheme('dark')
    else if (theme === 'dark') setTheme('system')
    else setTheme('light')
  }

  const label = theme === 'light' ? 'Switch to dark mode' : theme === 'dark' ? 'Switch to system theme' : 'Switch to light mode'

  return (
    <Button variant="ghost" size="icon" onClick={cycle} aria-label={label}>
      {theme === 'light' ? (
        <SunIcon className="h-4 w-4" />
      ) : theme === 'dark' ? (
        <MoonIcon className="h-4 w-4" />
      ) : (
        <MonitorIcon className="h-4 w-4" />
      )}
    </Button>
  )
}
