# Shot list and direction template

`direction.md` (RENDER) and `shoot/shoot-script.md` (FILM) both use this table. A RENDER direction
has no camera, so "visual" is the layout or frame, and the final section maps the direction onto
the Creative's own fields.

```
# Direction: <title>

## Shot list
| Time | Visual | On-screen text | VO / audio | Asset |
|------|--------|----------------|------------|-------|
| 0-1s (hook) | <frame 1: a concrete subject already moving, the result or the odd thing; never a title card, logo or empty scene> | <text on screen at 0 s> | <first-second line or action sound; "none" only on a photo slideshow> | <media id, "to film", or none> |
| 1-3s | ... | ... | ... | ... |

The hook row is Visual first, then On-screen, then Spoken or sound, all inside the first second.
The product name is not in it. The payoff lands inside the first third.

## Comment trigger and loop
- Comment trigger: <one planted, honest reason to comment or send; never "comment YES" bait>
- Loop: <pieces of 15 s or less: how the last frame flows back into the first; otherwise "none">

## Safe zones
- At 1080x1920 keep text at y >= 290 and above y=1490 (above about y=1250 when the caption is long),
  and out of the right 15% column (9:16; numbers in `references/platform-specs.md`).
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
