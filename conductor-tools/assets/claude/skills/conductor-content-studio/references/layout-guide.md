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
  9:16 and 4:5, so choose a photo with a bright, high-contrast subject, and keep the subject visible
  with `focalOverride` (e.g. `{"9x16": "30% 60%"}`). On 4:5 the headline sits at the top; on 1:1 it is
  small at the bottom.
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

If the subject's face sits in the top third, keep `bleed` and move the subject with `focalOverride`,
or switch to `card`. Don't switch to a layout whose 9:16 result is a dead panel just to avoid the
collision.
