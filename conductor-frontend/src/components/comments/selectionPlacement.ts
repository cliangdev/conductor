export const SELECTION_BUTTON_GAP = 8
/** Extra room under a touch selection for its drag handles. */
export const TOUCH_HANDLE_CLEARANCE = 22

/**
 * Top of the Comment button for a selection spanning `rectTop`..`rectBottom` (all in the same
 * coordinates). With a mouse the button sits above the words, so it never covers them. On a touch
 * device the native selection menu (copy, look up…) appears above the selection, so the button goes
 * below it, past the drag handles. When there is no room above (mouse) it also drops below.
 */
export function selectionButtonTop({
  rectTop,
  rectBottom,
  buttonHeight,
  coarse,
  minTop = 0,
}: {
  rectTop: number
  rectBottom: number
  buttonHeight: number
  coarse: boolean
  minTop?: number
}): number {
  if (coarse) return rectBottom + SELECTION_BUTTON_GAP + TOUCH_HANDLE_CLEARANCE
  const above = rectTop - buttonHeight - SELECTION_BUTTON_GAP
  return above < minTop ? rectBottom + SELECTION_BUTTON_GAP : above
}
