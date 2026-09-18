/**
 * The Conductor mark and wordmark.
 *
 * The mark is a hub: one coordinator node with four spokes out to four coordinated nodes. It says
 * what the product does (many agents, one place that steers them) without spelling the letter C.
 *
 * Geometry was chosen by rendering candidates from 14px to 120px in light, dark and boxed forms.
 * A first attempt with longer spokes and smaller outer nodes collapsed into a plain X, so the
 * spokes are short and there is a deliberate gap between each spoke and its node (r 3.1 centre,
 * r 2.1 outer, spokes stopping at 7.6/16.4 on a 24px grid).
 *
 * Known limit: below about 20px the round caps close the gaps and the mark reads as an X. That is
 * accepted for now; it is why the favicon leans on the accent tile to carry recognition. Do not
 * lengthen the spokes or shrink the nodes without re-checking 16px and 20px.
 *
 * `currentColor` throughout, so the mark inherits `text-primary` on light surfaces and white inside
 * the boxed lockup, and needs no light/dark variants.
 */

/** Spokes and nodes, shared by every renderer so the geometry lives in exactly one place. */
const SPOKES = [
  'M10.2 10.2 L7.6 7.6',
  'M13.8 10.2 L16.4 7.6',
  'M10.2 13.8 L7.6 16.4',
  'M13.8 13.8 L16.4 16.4',
]
const NODES: Array<[number, number, number]> = [
  [12, 12, 3.1],
  [5.2, 5.2, 2.1],
  [18.8, 5.2, 2.1],
  [5.2, 18.8, 2.1],
  [18.8, 18.8, 2.1],
]

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
      {SPOKES.map((d) => (
        <path key={d} d={d} />
      ))}
      {NODES.map(([cx, cy, r]) => (
        <circle key={`${cx}-${cy}`} cx={cx} cy={cy} r={r} fill="currentColor" stroke="none" />
      ))}
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
  // The hub needs a little more room than a single-stroke mark: its outer nodes sit near the edge
  // of the grid, so at 21px the gaps start to close. Each step is 2px larger than the lockup this
  // replaced, which keeps the nodes reading without changing the type size.
  const mark = { sm: 'h-5 w-5', md: 'h-[23px] w-[23px]', lg: 'h-[30px] w-[30px]' }[size]
  const text = { sm: 'text-[14px]', md: 'text-[15px]', lg: 'text-[20px]' }[size]

  return (
    <span className={`inline-flex items-center gap-2 ${className ?? ''}`}>
      <ConductorMark className={`${mark} shrink-0 text-primary`} />
      <span className={`${text} font-semibold tracking-[-0.015em] text-foreground`}>Conductor</span>
    </span>
  )
}
