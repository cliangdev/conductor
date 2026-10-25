/**
 * Whether a Creative render must run on this machine (the render core, with ffmpeg) rather than on the
 * server's instant path. MOTION and STILL always do. A CAROUSEL does when its audio is a track (the
 * slideshow MP4 is encoded here); a CLIP does when its audio is track, mix or none (its sound is
 * re-muxed here). A CLIP with `clip` or no audio, and a CAROUSEL without a track, stay on the server.
 * The backend and the frontend apply the same rule.
 */
export function needsLocalRender(kind: string | undefined, audio: { source?: string } | null | undefined): boolean {
  const source = audio?.source
  if (kind === 'CAROUSEL') return source === 'track'
  if (kind === 'CLIP') return source === 'track' || source === 'mix' || source === 'none'
  return true
}
