# Design critique

Passing checks does not make an ad good. `checks` and `passed` cover text spill, safe zones, contrast
and fonts. This critique covers craft, and it runs before the person sees a draft.

Look at the preview image at phone size. Vision models miss small typography details, so don't
claim to have judged kerning or hairline spacing; judge what is visible at a glance.

## Which criteria apply

Stills, ads and brand-layout creatives use the six criteria below. An organic slideshow (a
`compose_slideshow` CAROUSEL) uses the five slideshow criteria further down instead, because it is
meant to look native, not like an ad.

## Score each criterion 1 to 5 (stills and ads)

Score each one on its own, against the description, not against another draft.

1. **Thumb-stop**: would it stop a scroll? One clear focal point and a hook you get in a second.
2. **Headline size and position**: readable at thumbnail size, not crammed into a corner or a thin strip.
3. **Empty space**: no dead slab or empty panel. Space that is there on purpose is fine; a leftover band is not.
4. **Photo and copy together**: copy is not on the subject's face, and the photo carries the concept rather than decorating it.
5. **Product shown**: the product, or the result it gives, is visible. A stock pose or an empty scene scores 1 or 2.
6. **Not a banner ad**: it does not read as logo + badge + tagline stacked on a picture.

## Act on the scores

- Any criterion below 3: fix it (copy, layout, `focalOverride`, `typeOverrides`, the photo) and
  render again. At most two fix rounds.
- Still below 3 after that: show the best version with one honest line on what is weak.
- Comparing variants: score each on its own first. Use a side-by-side comparison only to break a
  tie, and judge it in both orders (first vs. second, then second vs. first); if the order changes
  the winner, call it a tie. Pairwise judging favours whichever is shown first.

## Show the scores

One compact line next to the preview, for example:
`Craft: thumb-stop 4, headline 4, space 3, photo+copy 4, product 3, not-banner 4. Weakest: product is implied, not shown.`

## Slideshow criteria (organic slideshows)

Look at the contact sheet: slide 1 at full size, then the slides in order. Score each 1 to 5.

1. **Slide 1 stops the scroll**: a human tension or a result people want, in a line a stranger gets in a second. A feature, the app name or a price scores 1 or 2.
2. **Each slide earns the next swipe**: every slide has one job and a reason to go on; no filler slide, no slide that repeats the last.
3. **People would save or send it**: the middle slides hold something to keep or to send to a named person.
4. **The app is the payoff, not the opener**: it appears on slide N-1 on a real screenshot with one plain mention, never on slide 1 and never as a logo, badge or offer.
5. **Native look**: text boxes and photos feel like a person's post. Banner-ad signs (a logo lockup, a badge, a tagline stack, a call-to-action button) score 1 or 2.

Act on the scores as below. Show them in one line, for example:
`Slideshow: scroll-stop 4, next-swipe 3, save/send 4, app-as-payoff 4, native 5. Weakest: slide 3 repeats slide 2.`
