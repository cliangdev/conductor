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

/** Reads and JSON-parses a value, returning `fallback` if missing, malformed, or if localStorage
 * throws (private browsing / quota exceeded). Used for structured drafts (e.g. the Work Item
 * review-mode pending-comment state) rather than the enum-like values above.
 *
 * Note: this does NOT validate the parsed value's shape — `JSON.parse` happily returns a stale shape
 * left behind by a previous release, or anything else stored under `key`, cast unchecked to `T`. Pass
 * `isValid` whenever the caller can't tolerate a malformed value slipping through (it does — falling
 * back to `fallback` on a mismatch, same as a parse failure). */
export function readPersistedJSON<T>(key: string, fallback: T, isValid?: (v: unknown) => v is T): T {
  try {
    const stored = localStorage.getItem(key)
    if (stored !== null) {
      const parsed: unknown = JSON.parse(stored)
      if (!isValid || isValid(parsed)) return parsed as T
    }
  } catch { /* private browsing / quota exceeded / malformed JSON */ }
  return fallback
}

/** Writes a value as JSON, silently no-op-ing on failure. */
export function writePersistedJSON<T>(key: string, value: T): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch { /* private browsing / quota exceeded */ }
}

/** Removes a persisted key, silently no-op-ing on failure. */
export function removePersisted(key: string): void {
  try {
    localStorage.removeItem(key)
  } catch { /* private browsing / quota exceeded */ }
}
