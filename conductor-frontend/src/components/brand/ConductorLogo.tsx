/**
 * The Conductor mark and wordmark.
 *
 * The mark is a `C` drawn as one open arc whose lower terminal thickens into a filled dot: the tip
 * of a conductor's baton at the end of a stroke. Geometry was chosen by rendering candidates at
 * 14px through 88px and picking what stayed legible small (r 8.5, 38° gap, stroke 2.4, dot 2.5 on a
 * 24px grid). Do not thin the stroke or shrink the dot without checking 16px again.
 *
 * `currentColor` throughout, so the mark inherits `text-primary` on light surfaces and white inside
 * the boxed lockup, and needs no light/dark variants.
 */

export function ConductorMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.4"
      strokeLinecap="round"
      aria-hidden="true"
      className={className}
    >
      <path d="M18.70 6.77 A8.5 8.5 0 1 0 18.70 17.23" />
      <circle cx="18.70" cy="17.23" r="2.5" fill="currentColor" stroke="none" />
    </svg>
  )
}

/**
 * The mark on the accent tile, for places that need the logo to hold its own against an arbitrary
 * background: the favicon, the Open Graph card, an avatar slot.
 */
export function ConductorMarkBoxed({ className }: { className?: string }) {
  return (
    <span
      className={`inline-grid place-items-center rounded-[22%] bg-primary text-primary-foreground ${className ?? ''}`}
    >
      <ConductorMark className="h-[62%] w-[62%]" />
    </span>
  )
}

/**
 * Mark plus wordmark, the default lockup. `size` picks the pairing of mark and type that was
 * spaced together; anything else drifts out of alignment.
 */
export function ConductorLogo({
  size = 'md',
  className,
}: {
  size?: 'sm' | 'md' | 'lg'
  className?: string
}) {
  const mark = { sm: 'h-[18px] w-[18px]', md: 'h-[21px] w-[21px]', lg: 'h-7 w-7' }[size]
  const text = { sm: 'text-[14px]', md: 'text-[15px]', lg: 'text-[20px]' }[size]

  return (
    <span className={`inline-flex items-center gap-2 ${className ?? ''}`}>
      <ConductorMark className={`${mark} shrink-0 text-primary`} />
      <span className={`${text} font-semibold tracking-[-0.015em] text-foreground`}>Conductor</span>
    </span>
  )
}
