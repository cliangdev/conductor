# What Conductor can render

Read this before tagging an idea `RENDER` or `FILM`, and before writing a spec. An idea is `RENDER`
only if every card or frame it needs fits what is listed here, using media that is in
`context/media.json` or that the person has offered as a local file. Anything else is `FILM`, or it
needs changing until it fits.

## Kinds

- **STILL**: one photo with the brand layout over it: headline, optional body, logo lockup and CTA.
  It renders once per placement.
  - **Carousel**: 2–10 cards, rendered at one ratio (default 4:5).
  - **Story**: 2–7 beats, rendered at the `story` placement.
  - Each card or beat can take its own photo; a beat without one inherits the main photo. Every beat
    after the first needs a headline.
- **MOTION**: a short video Conductor animates itself, 3–60 s (default 8 s).
  - Text animation presets: `fade-up`, `word-by-word`, `accent-pop` or `none`.
  - Background: a photo with a slow zoom or pan, or a clip from the library.
  - An end card holds the finished frame for the last 2 s.
  - Audio: the clip's own sound, a library music track, or none.
  - This is the right tool for a motion-text post: **it does not need filming.**
- **CLIP**: a finished video posted as-is. There is no brand layout and nothing to draft. It only
  applies when the person already has the video.

## Hard requirements (a draft is refused without them)

- **A photo.** Every STILL needs one, and so does a carousel or story, whose beats inherit the main
  photo. A MOTION needs a photo or a clip as its background. There are no type-only stills. If
  `media.json` has no usable (unblocked) image, a `RENDER` idea needs the person to supply a photo.
- **A headline.** If the Brand Kit requires an accent phrase, the headline needs exactly one
  `*accent phrase*`.
- **Copy rules and approved lines.** Every Brand Kit copy rule applies to the headline, body,
  caption and every beat. Approved lines must run word for word.
- **Real values.** Layouts, themes and placements must exist. The validator names the valid ones
  if not.

## Layouts and controls

- **Layouts:** `stacked`, `bleed` and `split` are dark only; `card` is dark or light. The photo and
  text arrangement is fixed per layout.
- **Placements:** the kit's enabled placements (usually 9x16, 4x5, 1x1). You can also opt in to
  `story`, `1.91x1`, `2x3` and `16x9`.
- **`typeOverrides`:** headline size per placement. Use this when text reads too small or too large;
  don't switch layouts to fix it.
- **`layoutOverrides`:** photo height or focal point, for example to make the photo fill more of a
  9:16 frame.
- **Safe zones:** checked automatically at preview, on every placement. A blocking failure, such as
  text or a badge under the platform's buttons, is fixed before anyone is asked to approve.
- **The caption is never drawn on the artwork.** It goes with the post.

## What it cannot do (so the idea needs filming or a rethink)

- Footage of people, places, cooking or the product in use. Screen recordings of an app.
- Voiceover or spoken dialogue: MOTION has music or clip sound, not speech.
- Grids, charts, collages of several photos on one card, illustrations, or generated images.
  Each card is one photo plus text.
- Anything that relies on a product claim the brief can't confirm. That's a copy problem, not a
  rendering one: soften the claim or drop the idea.
