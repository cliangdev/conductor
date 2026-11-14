# Idea template

Every idea in `ideas/angle-<n>.md` uses exactly this template, so the merged pool reads the same
whichever agent wrote it. Keep the word caps; the judge is told not to reward length, and equal
length also keeps the comparison fair.

Heading: `## Idea <n>: <title>` where the title is 8 words or fewer.

```
## Idea <n>: <title, max 8 words>

- **Concept:** <one line, max 25 words: what the viewer sees and why it works>
- **Hook, visual:** <max 15 words: what frame 1 shows. A concrete subject, already moving, the result or the odd thing; never a title card, logo or empty scene>
- **Hook, on screen:** <text at 0 s, max 12 words; on the artwork aim for about 8 or fewer, `voice.md`>
- **Hook, spoken or sound:** <max 12 words, or the sound that belongs to the action, or "none" (photo slideshow only)>
- **Hook type:** <question | bold-claim | pattern-interrupt | relatable-pain | how-to | number | curiosity-gap | story | social-proof; tag it from `hooks.md`'s hook library (or `voice.md`'s "Shapes, and the hookType each one is tagged" table)>
- **Comment trigger:** <max 15 words: one planted, honest reason to comment or send (a choice to argue about, a question only the viewer can answer, a deliberate small omission); never "comment YES" bait>
- **Loop:** <pieces of 15 s or less: how the last frame flows into the first, max 15 words; otherwise "none">
- **Payoff at:** <seconds; must fall inside the first third of the piece>
- **Format:** <slideshow | still | carousel | story sequence | motion text over photo | motion text over clip | talking head | demo | skit | other>
- **Platform:** <primary platform, then any others>
- **Tag:** RENDER | FILM
- **Why this audience cares:** <max 30 words, tied to the brief's tension>
- **Assets needed:** <ids and labels from media.json, or "none", or what must be filmed>
- **Follows:** <optional: the Proven or Leaning lesson ids from the brief that this idea follows, e.g. `#3,#7`, at most 5; never a Didn't work or Taste id>
- **Tests:** <optional: `H<n>`, only when the brief names that hypothesis and this idea is the one that tests it (change only its one variable); otherwise leave the line out>

Draft spec (RENDER only):

- kind: STILL | MOTION | slideshow (organic post: name the archetype and slide count, 5 to 7, instead of the fields below)
- layout: <a layout the brand kit and registry support>
- theme: dark | light
- headline: <with exactly one *accent phrase* if the kit requires it>
- body: <max 20 words, or none>
- caption: <the post caption, within the platform's limit>
- photoId: <id from media.json, or none>
- imagePrompt: <only when media.json has `generate: true` and no library photo fits: the scene in one line, no text in the image, no product depiction>
- sequence: <for a story or carousel only: one line per beat, "headline | body">
- motion: <MOTION only: preset, durationSec, background>
```

The idea's format maps to the publish pack's `tags.format` as: slideshow -> `carousel`; still -> `still`; carousel ->
`carousel`; story sequence -> `story`; motion text over photo or clip -> `motion`; talking head,
demo, skit or other -> `film`.

## Hook rules

The hook has three layers, all in the first second (`hooks.md`). The product or brand name is never
in it. Frame 1 passes the "what was that?" test. Organic video and slide 1 take the native look
(plain platform-style text; `slideshow-formats.md`).

## Sound

An idea may not rely on a trending song. A business account cannot license most trending tracks, so
name original audio or a Commercial Music Library track, or leave sound out of the concept.

## Tagging honestly

- `RENDER` means Conductor can make it end to end: a still, a carousel, a story sequence, or motion
  text over photos or clips that exist in `media.json` (or a generated photo, via `imagePrompt`, when `media.json` has `generate: true`; there are no type-only renders). Nothing in it
  requires a person to be filmed or footage that is not on hand.
- `FILM` means it needs real filming: a person speaking to camera, a demonstration, a skit, a
  product in use. A `FILM` idea has no draft spec; it gets a shoot-ready script instead.
- Tag against the media actually on hand. If the idea needs a clip that is not in `media.json`, it
  is `FILM`, or it is `RENDER` with the missing photo named under "Assets needed" as something the
  user must supply.

## Distinctness

Within one angle file no two ideas may share a mechanic (for example two list posts, or two
before/after reveals). Differ in the hook device, the format, or the emotional register.
