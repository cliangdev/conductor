# Idea template

Every idea in `ideas/angle-<n>.md` uses exactly this template, so the merged pool reads the same
whichever agent wrote it. Keep the word caps; the judge is told not to reward length, and equal
length also keeps the comparison fair.

Heading: `## Idea <n>: <title>` where the title is 8 words or fewer.

```
## Idea <n>: <title, max 8 words>

- **Concept:** <one line, max 25 words: what the viewer sees and why it works>
- **Hook, spoken:** <max 12 words, or "none (text only)">
- **Hook, on screen:** <max 12 words>
- **Hook type:** <question | bold-claim | pattern-interrupt | relatable-pain | how-to | number | curiosity-gap | story | social-proof>
- **Format:** <still | carousel | story sequence | motion text over photo | motion text over clip | talking head | demo | skit | other>
- **Platform:** <primary platform, then any others>
- **Tag:** RENDER | FILM
- **Why this audience cares:** <max 30 words, tied to the brief's tension>
- **Assets needed:** <ids and labels from media.json, or "none", or what must be filmed>

Draft spec (RENDER only):

- kind: STILL | MOTION
- layout: <a layout the brand kit and registry support>
- theme: dark | light
- headline: <with exactly one *accent phrase* if the kit requires it>
- body: <max 20 words, or none>
- caption: <the post caption, within the platform's limit>
- photoId: <id from media.json, or none>
- sequence: <for a story or carousel only: one line per beat, "headline | body">
- motion: <MOTION only: preset, durationSec, background>
```

## Sound

An idea may not rely on a trending song. A business account cannot license most trending tracks, so
name original audio or a Commercial Music Library track, or leave sound out of the concept.

## Tagging honestly

- `RENDER` means Conductor can make it end to end: a still, a carousel, a story sequence, or motion
  text over photos or clips that exist in `media.json` (or need no photo at all). Nothing in it
  requires a person to be filmed or footage that is not on hand.
- `FILM` means it needs real filming: a person speaking to camera, a demonstration, a skit, a
  product in use. A `FILM` idea has no draft spec; it gets a shoot-ready script instead.
- Tag against the media actually on hand. If the idea needs a clip that is not in `media.json`, it
  is `FILM`, or it is `RENDER` with the missing photo named under "Assets needed" as something the
  user must supply.

## Distinctness

Within one angle file no two ideas may share a mechanic (for example two list posts, or two
before/after reveals). Differ in the hook device, the format, or the emotional register.
