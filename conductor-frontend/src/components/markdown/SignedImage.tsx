'use client'
import { useState } from 'react'

interface Props {
  src?: string
  alt?: string
}

// Parses "alt text|300" or "alt text|50%" — the suffix after the last | sets the width.
function parseAlt(raw: string | undefined): { label: string; width?: string } {
  if (!raw) return { label: '' }
  const match = raw.match(/^(.*)\|(\d+%?)$/)
  if (!match) return { label: raw }
  const raw_width = match[2]
  return { label: match[1], width: raw_width.endsWith('%') ? raw_width : `${raw_width}px` }
}

export function SignedImage({ src, alt }: Props) {
  const [resolvedSrc] = useState(src)
  const { label, width } = parseAlt(alt)
  // eslint-disable-next-line @next/next/no-img-element
  return (
    <img
      src={resolvedSrc}
      alt={label}
      className="rounded"
      style={width ? { width, maxWidth: '100%' } : { maxWidth: '100%' }}
    />
  )
}
