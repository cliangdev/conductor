export interface RailLayoutItem {
  id: string
  /** Where the card wants to sit: the top of its highlight, in rail coordinates. */
  top: number
  /** Measured card height. */
  height: number
}

export interface RailLayout {
  /** Top of each card, by id. */
  positions: Record<string, number>
  /** Height needed to hold every card. */
  totalHeight: number
}

export const RAIL_GAP = 8

/**
 * Lines the cards up with their highlights without overlapping. Cards are ordered by wanted top (ties
 * keep the given order). The active card sits exactly at its anchor and the others are pushed away from
 * it, down for those after it and up for those before it, keeping `gap` between neighbours. Nothing
 * goes above `floor`, so cards pushed up by a low active card stack down from the top instead.
 */
export function layoutRail(
  items: RailLayoutItem[],
  activeId: string | null,
  gap: number = RAIL_GAP,
  floor = 0
): RailLayout {
  // Array.prototype.sort is stable, so equal tops keep their given order.
  const list = items.slice().sort((a, b) => a.top - b.top)
  const pos = list.map((i) => i.top)
  const a = activeId === null ? -1 : list.findIndex((i) => i.id === activeId)

  if (a >= 0) {
    for (let i = a + 1; i < list.length; i++) {
      pos[i] = Math.max(list[i].top, pos[i - 1] + list[i - 1].height + gap)
    }
    for (let j = a - 1; j >= 0; j--) {
      pos[j] = Math.min(list[j].top, pos[j + 1] - list[j].height - gap)
    }
  }
  // The floor pass also settles the no-active case: each card clears the one above it.
  for (let k = 0; k < list.length; k++) {
    const min = k === 0 ? floor : pos[k - 1] + list[k - 1].height + gap
    pos[k] = Math.max(pos[k], min)
  }

  const positions: Record<string, number> = {}
  let totalHeight = 0
  list.forEach((it, i) => {
    positions[it.id] = pos[i]
    totalHeight = Math.max(totalHeight, pos[i] + it.height)
  })
  return { positions, totalHeight }
}
