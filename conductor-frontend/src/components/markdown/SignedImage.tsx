'use client'
import { useState } from 'react'

interface Props {
  src?: string
  alt?: string
}

export function SignedImage({ src, alt }: Props) {
  const [resolvedSrc] = useState(src)
  // For MVP: render signed URL as-is — the storageUrl from the document response is already signed.
  // The issue page re-fetches documents on mount and checks storageUrlExpiresAt to refresh
  // before expiry, so the URL passed here is always fresh.
  // eslint-disable-next-line @next/next/no-img-element
  return <img src={resolvedSrc} alt={alt ?? ''} className="max-w-full rounded" />
}
