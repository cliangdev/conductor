<!-- Keep identical to the copy in the other creative skill (conductor-creative / conductor-content-studio references/layout-guide.md). Skills install independently and cannot reference each other. -->
# Layout guide

Pick the layout on purpose. A Creative has one layout across all its placements (9:16, 4:5, 1:1), so
choose it for the hardest placement, usually 9:16. Never rely on the registry default: it is
`stacked`, which is the wrong pick whenever 9:16 is enabled.

These notes come from rendering the same photo and headline in every layout with
`preview_creative_draft`.

## Default

- 9:16 enabled: **`bleed`**. Use **`card`** when the photo itself must stay bright and readable.
- Only 4:5 and 1:1: `split` or `card` both work.

## The layouts

- **`bleed`**: full-frame photo with a large headline. It is the only layout that fills 9:16 with a
  big headline. Dark theme only (`theme: "light"` is refused). Its scrim darkens most of the photo on
  9:16 and 4:5, so choose a photo with a bright, high-contrast subject. On 4:5 the headline sits at
  the top, so a face in the top third of the photo lands under it; on 1:1 the headline is small at the
  bottom.
- **`card`**: the photo as a framed card with a large headline beneath. On 9:16 the bottom third is
  an empty dark panel. Use it when the photo must stay readable, such as a product screen that
  bleed's scrim would hide.
- **`split`**: good on 4:5, with a big headline under the photo. Bad on 9:16: the photo sits in the
  top third, a large dark panel is left empty, the headline is small, and it failed the 9:16 bottom
  safe-zone check. Don't use it when 9:16 is enabled.
- **`stacked`**: on 9:16 the photo band leaves an empty dark slab and the headline is forced small
  (the 430 px bottom safe zone is solid fill). Don't use it when 9:16 is enabled. Fine for 4:5.

## 1:1 headline size

On 1:1 every layout auto-fits the headline small (about 60 px at most). Pin it with `typeOverrides`,
e.g. `{"1x1": [88]}`; 92 passed the checks with an eight-word headline. A pinned size is not shrunk to
fit, so read `checks` again after pinning.

## Logo

`lockup: "chip"` puts the logo on a white pill. Use it when the photo behind the logo is busy.

## Photo and layout together

`focalOverride` (e.g. `{"4x5": "50% 80%"}`) only moves the subject when the crop leaves spare room:
a photo much taller than the placement's frame, or `card`'s wide strip on 1:1. A photo already close
to the placement's shape (a 4:5-ish photo on 4:5) has nothing to shift, and the override does nothing.
Check the next preview rather than assuming it worked.

If the subject's face would sit under the headline: try `focalOverride` where there is room, else pick
a photo with the subject lower in the frame, else use `card` (and pin `card`'s 1:1 crop with
`focalOverride` so the face isn't cut off). If two fix rounds still leave a weak frame, the photo is
usually the problem: say so and offer a different one.
