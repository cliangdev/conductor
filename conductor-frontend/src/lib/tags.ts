const RESERVED_TAGS = new Set(['default', 'system'])

export function isReservedTag(value: string): boolean {
  return RESERVED_TAGS.has(value.trim().toLowerCase())
}
