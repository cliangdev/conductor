// The three next-themes options, rendered identically in the Sidebar's user menu and the command
// palette's Theme group — one list so adding a theme only requires editing here.

import { MonitorIcon, MoonIcon, SunIcon, type LucideIcon } from 'lucide-react'

export interface ThemeOption {
  value: 'light' | 'dark' | 'system'
  label: string
  icon: LucideIcon
}

export const THEME_OPTIONS: ThemeOption[] = [
  { value: 'light', label: 'Light', icon: SunIcon },
  { value: 'dark', label: 'Dark', icon: MoonIcon },
  { value: 'system', label: 'System', icon: MonitorIcon },
]
