# Shot list and direction template

`direction.md` (RENDER) and `shoot/shoot-script.md` (FILM) both use this table. A RENDER direction
has no camera, so "visual" is the layout or frame, and the final section maps the direction onto
the Creative's own fields.

```
# Direction: <title>

## Shot list
| Time | Visual | On-screen text | VO / audio | Asset |
|------|--------|----------------|------------|-------|
| 0-3s | ... | ... | ... | <media id, "to film", or none> |

## Safe zones
- Keep text out of the top ~14%, the bottom ~35% and ~6% at each side (9:16; check current
  platform guidance).
- Headline and CTA sit in the middle band. Nothing on a face.

## Sound
<sound on or off by design; music track id if any; voice-over; captions are always on screen>

## Publish assets
Per platform the script names:
- Cover frame: <video: timestamp in seconds and what is on it; carousel: card 1; still: n/a>
- Alt text: <one line per image or card, in order>
- On-screen text placement: <where each line sits, inside the safe zone for that platform>
- Sound source and licence: <original audio | Commercial Music Library track | library track id
  and licence | clip's own sound | none>. Never a trending track a business account cannot license.

## Capture settings (FILM only)
9:16 vertical, 1080x1920, 30 fps, sound on, lit from the front, phone steady, shoot extra B-roll.

## Ready-to-paste text (FILM only)
<on-screen text lines in order, and the captions per platform>

## Mapping to the Creative (RENDER only)
- kind: STILL | MOTION
- layout / theme: <from the kit and registry>
- sequenceKind: story | carousel | none   (carouselRatio for a carousel)
- motion: preset (fade-up | word-by-word | accent-pop | none), durationSec, background (photo or
  clip, with its motion)
- audio: clip | track | none
```
