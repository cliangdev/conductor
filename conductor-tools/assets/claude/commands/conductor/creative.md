---
name: conductor:creative
description: One entry point for Conductor creative work — checks the workspace brand, offers to set it up if it's never been configured, asks what to make (static ad, short video, an existing clip), gathers the few inputs needed, then hands off to the conductor-creative skill through render, preview and attach.
allowed-tools: mcp__conductor__*, AskUserQuestion, Skill, WebFetch, Read, Glob, Grep
---

# /conductor:creative

Guided entry point for making a Post's artwork — a static ad, a short video, or posting a video
already on hand. Keeps the interaction short: a couple of questions, then it hands off to the
`conductor-creative` skill (`Skill(skill: "conductor-creative")`) for the actual writing, rendering
and judgment calls. This command doesn't replace that skill's guidance — read it once invoked and
follow it.

## Trigger

`/conductor:creative`, or a request to make/design a social ad, a short video, or post a clip.

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

## Step 2 — What to make

```json
{
  "questions": [{
    "question": "What would you like to create?",
    "header": "Creative Type",
    "options": [
      {"label": "Static ad", "description": "A photo + headline, rendered at every enabled placement"},
      {"label": "Short video from a photo", "description": "Animated text over a still photo or a moving background"},
      {"label": "Video from a clip I have", "description": "Animate the brand layout using an existing video as background"},
      {"label": "Post an existing video as-is", "description": "No brand layout — the finished clip goes up directly"},
      {"label": "Test a headline variant", "description": "A lettered variant of an existing Creative, for a hook test"}
    ],
    "multiSelect": false
  }]
}
```

## Step 3 — Gather the few inputs that choice needs

Keep this light — one or two follow-up questions, not a form:

- **Static ad / short video from a photo**: the headline (or let the skill draft one from the
  Knowledge page), and a photo. Call `list_creative_media({kind: "IMAGE"})` first and offer what's
  already on hand before asking for a new upload.
- **Video from a clip I have / Post an existing video as-is**: call
  `list_creative_media({kind: "VIDEO"})` first; if nothing fits, ask for a local path or URL.
- **Test a headline variant**: ask which existing Creative (id or display id like `12a`) and the new
  headline.

## Step 4 — Hand off to the skill

Call `Skill(skill: "conductor-creative")` and follow it from wherever this command's gathering left
off — angle, copy rules, layout, `create_creative`/`update_creative`, render, look at the result
honestly, readiness. Don't re-derive what that skill already covers.

## Step 5 — Finish with a render → preview → attach, or a clear next step

Once the skill's readiness check passes: full `render_creative`, `preview_creative` to show the
result, and `attach_creative_to_post` if there's already a Post to attach it to (ask for the Post's
id if not obvious from context). If there is no Post yet, say so plainly and name the next step —
creating one via the `conductor-publisher` skill — rather than leaving the artwork stranded with no
stated destination.
