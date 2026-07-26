/**
 * One GFM task list item. Tolerates leading indent (nested lists), blockquote prefixes, all four
 * marker forms (`-` `*` `+` and `1.` / `1)`), and either checked state. Group 1 is everything up to
 * and including the opening bracket; the state character follows it.
 *
 * Kept in sync with `TASK_LIST_ITEM` in the backend's `ProjectDocService` — both sides flip the same
 * line, one optimistically and one for real, so a mismatch would show a box that snaps back.
 */
const TASK_LIST_ITEM = /^(\s*(?:>\s*)*(?:[-*+]|\d{1,9}[.)])\s+\[)[ xX]\]/

/**
 * Returns `content` with the checkbox on `lineNumber` (1-based, against the raw content) set to
 * `checked`. Returns null if that line is not a task list item — which means the document has changed
 * since it was rendered, and the caller should not write.
 *
 * Only the single state character is rewritten, so line count, indentation, marker style and any
 * trailing `\r` on CRLF content are all preserved.
 */
export function toggleTaskLine(
  content: string,
  lineNumber: number,
  checked: boolean
): string | null {
  const lines = content.split('\n')
  if (lineNumber < 1 || lineNumber > lines.length) return null

  const line = lines[lineNumber - 1]
  const match = TASK_LIST_ITEM.exec(line)
  if (!match) return null

  const prefix = match[1]
  lines[lineNumber - 1] = prefix + (checked ? 'x' : ' ') + line.slice(prefix.length + 1)
  return lines.join('\n')
}
