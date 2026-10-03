# Brand Kit check and setup

Shared by `/conductor:creative` and the `conductor-creative` skill: check whether this workspace's
Brand Kit has been set up, and offer to set it up if not. The text below is the command's Step 1,
unchanged.

## Step 1 — Check the brand

Call `get_brand_kit` (pass `kitId` only if the person already named a specific brand).

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
  available, `upload_brand_image` for the `mark` slot at least. Verify with `get_brand_kit` —
  `configured` should now read `true`.
- **Skip for now**: proceed; the skill below will render against the default kit as given.
