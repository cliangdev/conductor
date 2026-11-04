// A small word-level diff (longest common subsequence over words), enough to highlight what a copy
// edit changed without a diff dependency. Whitespace is kept as tokens so the pieces rejoin to the
// original text exactly.

export interface DiffPiece {
  text: string
  /** `same` appears on both sides, `del` only in the old text, `add` only in the new. */
  kind: 'same' | 'del' | 'add'
}

const tokens = (s: string): string[] => s.match(/\s+|[^\s]+/g) ?? []

/** Inputs longer than this many tokens skip the quadratic table and show as one replaced block. */
const MAX_TOKENS = 600

export function wordDiff(oldText: string, newText: string): DiffPiece[] {
  if (oldText === newText) return oldText ? [{ text: oldText, kind: 'same' }] : []
  const a = tokens(oldText)
  const b = tokens(newText)
  if (a.length > MAX_TOKENS || b.length > MAX_TOKENS) {
    return [
      ...(oldText ? [{ text: oldText, kind: 'del' as const }] : []),
      ...(newText ? [{ text: newText, kind: 'add' as const }] : []),
    ]
  }
  // lcs[i][j] = length of the longest common subsequence of a[i..] and b[j..]
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0))
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i]![j] = a[i] === b[j] ? lcs[i + 1]![j + 1]! + 1 : Math.max(lcs[i + 1]![j]!, lcs[i]![j + 1]!)
    }
  }
  const out: DiffPiece[] = []
  const push = (kind: DiffPiece['kind'], text: string) => {
    const last = out[out.length - 1]
    if (last && last.kind === kind) last.text += text
    else out.push({ text, kind })
  }
  let i = 0
  let j = 0
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      push('same', a[i]!)
      i++
      j++
    } else if (lcs[i + 1]![j]! >= lcs[i]![j + 1]!) {
      push('del', a[i++]!)
    } else {
      push('add', b[j++]!)
    }
  }
  while (i < a.length) push('del', a[i++]!)
  while (j < b.length) push('add', b[j++]!)
  return out
}
