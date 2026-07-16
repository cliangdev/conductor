'use client'

// The one try/catch wrapper around localStorage reads/writes — shared by the Work Item list's sort
// key and display-mode/explicit-preference flags (useWorkItemListState, WorkItemListView), all of
// which must degrade silently rather than throw (private browsing, storage quota, SSR).

import { useState } from 'react'

/** Reads a raw string value, returning `fallback` if missing, invalid, or if localStorage throws. */
export function readPersisted<T extends string>(key: string, isValid: (v: string) => v is T, fallback: T): T {
  try {
    const stored = localStorage.getItem(key)
    if (stored !== null && isValid(stored)) return stored
  } catch { /* private browsing / quota exceeded */ }
  return fallback
}

/** Reads a boolean-ish flag — any stored value (not just `'1'`) counts as set. */
export function readPersistedFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) !== null
  } catch {
    return false
  }
}

/** Writes a value, silently no-op-ing on failure. */
export function writePersisted(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch { /* private browsing / quota exceeded */ }
}

/** `useState` mirrored to localStorage under `key` — for simple enum-like preferences (e.g. sort key). */
export function usePersistedState<T extends string>(
  key: string,
  isValid: (v: string) => v is T,
  fallback: T
): [T, (next: T) => void] {
  const [value, setValue] = useState<T>(() => readPersisted(key, isValid, fallback))
  function set(next: T) {
    setValue(next)
    writePersisted(key, next)
  }
  return [value, set]
}
