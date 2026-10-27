# Brand Kit check and setup

Used by the `conductor-marketing-create` skill (both modes): check whether this workspace's
Brand Kit has been set up, and offer to set it up if not.

## Step 1 — Check the brand

Call `get_brand_kit` for the kit chosen under SKILL.md "Brand kit first" (`kitId` if the person
named a brand or already chose one; otherwise no `kitId`, which returns the default and any
`otherKits`). Setup applies to that kit only: pass its `kitId` to `update_brand_kit` and
`upload_brand_image`.

**If `configured` is `false`**, this workspace has no brand set up yet — offer to fix that before
making anything:

```json
{
  "questions": [{
    "question": "This workspace's Brand Kit hasn't been set up yet (default colours, no font, no logo). Set it up now?",
    "header": "Brand Kit",
    "options": [
      {"label": "From a website", "description": "Fetch a URL and infer colours, voice and name"},
      {"label": "From a brand doc", "description": "Read a local file (guidelines, style doc)"},
      {"label": "Answer a few questions", "description": "Name, colours, font, CTA line, approved lines"},
      {"label": "Skip for now", "description": "Proceed with the default kit as-is"}
    ],
    "multiSelect": false
  }]
}
```

- **From a website**: ask for the URL, `WebFetch` it, infer a plausible accent colour, name, CTA
  claim and a couple of approved lines from the copy on the page. Show what you inferred and confirm
  before writing.
- **From a brand doc**: ask for the file path, `Read` it, extract the same fields, confirm before
  writing.
- **Answer a few questions**: ask for name, primary/accent colour (hex or a description you convert),
  font (if any), the CTA claim, and any lines that must run verbatim. Keep it to one AskUserQuestion
  batch, not a long interview.
- Once confirmed, call `update_brand_kit` with whatever was gathered. If a logo file or URL is
  available, `upload_brand_image` for the `mark` slot at least. Warn before setting a badge image or
  a `ctaClaim`: both render on every still, which makes each one read as a banner ad. Suggest putting
  a trial offer or similar in the caption instead. Verify with `get_brand_kit` —
  `configured` should now read `true`.
- **Skip for now**: proceed; the skill below will render against the default kit as given.

A kit can also carry a slideshow style (the look of organic slideshows); it is optional, and native is
the default. `update_brand_kit` sets it (`slideshowStyle`, fields in `references/slideshow-formats.md`).

## Make a kit style from slides

Triggers: "I like these slides", "I like this look", "make a kit (style) from it". Never change the
default kit, and never delete anything.

1. **Identify the slides and read their style.**
   - A Creative (display id such as `12a`): `get_creative` and read `slideStyle`. A CAROUSEL with a
     null `slideStyle` was made before styles were recorded: treat it as the native defaults and say so.
   - A local slideshow folder (`.conductor/slideshows/*/spec.json`): read the spec's top-level `resolvedStyle`
     (the full look that was composed; the spec's own `style` is only the partial input). A folder
     with neither `resolvedStyle` nor `style` was made before styles were recorded: treat it as the
     native defaults and say so (the same as a Creative with a null `slideStyle`).
   - Images designed elsewhere: look at them, sample the colours (text box, text, outline, accent),
     and ask the person for the font name (and a Google Fonts link if it is one). Compose one sample
     slide with `compose_slideshow` and that inline `style`, show it, and continue only once they
     say it matches.
2. **Ask once** (`AskUserQuestion`): a new kit, or add it to an existing kit.
   - **New kit:** ask for a name. Offer `copyFrom` the current kit so copy rules, voice, tokens and
     font carry over; call `create_brand_kit({name, slideshowStyle, copyFrom?})`. Logos are not
     copied: offer `upload_brand_image` for the new kit's slots.
   - **Existing kit:** `get_brand_kit({kitId})` first. If it already has a `slideshowStyle`, say it
     will be replaced and show the old one, then `update_brand_kit({kitId, slideshowStyle})`.
3. **Verify** with `get_brand_kit({kitId})`: `slideshowStyle` matches what was sent. Report the kit
   name and how to use it (pick it at the kit question, or pass `brandKitId` to `compose_slideshow`).
