---
name: conductor-creative
description: Creates on-brand ad creative through the Conductor MCP tools — a photo plus headline rendered at every enabled placement (e.g. TikTok/Reels 9:16, Instagram feed 4:5, Facebook 1:1, plus any opt-in placements) from a workspace's Brand Kit, rendered locally on this machine and checked by eye before it's called done. Use when asked to make, design, write or export a social ad, an ad creative, a promo image, a static ad, a story or carousel sequence, or a new lettered variant (headline test) of one.
user-invocable: true
allowed-tools: mcp__conductor__*, AskUserQuestion, Read, Glob, Grep
---

# Conductor Creative

Turns one concept — a photo and a headline — into upload-ready artwork at every placement a
workspace's Brand Kit enables. This is the front half of Conductor's marketing pillar: what this
skill produces becomes a Post's media, and `conductor-publisher` takes it from there through review
and out to each destination. You write the ad here; you do not post it.

You are writing the ad, not just running a tool. The copy and the visual language both come from
things that already exist for this workspace — a Brand Kit (data: tokens, copy rules, approved
lines) and a Knowledge page (prose: positioning, voice, facts). Read them rather than inventing.

Rendering happens locally, through this MCP server, with a browser on this machine — there is no
separate render service to wait on. `render_creative` typically finishes in well under a minute.

## How to make a Creative

### 1. Read the brand

Call `get_brand_kit` (a project may have more than one — pass `kitId` if the person named a
specific brand, otherwise the default). It gives you the colour tokens, font, logo/wordmark/badge
URLs, the CTA claim, the copy rules (each a regex, a message, and which fields it applies to), the
approved lines, the enabled placements, and `knowledgePagePath` — the wiki page holding this
brand's positioning, message hierarchy, approved verbatim lines and voice in prose. Read that page
with `read_knowledge_pages` before writing anything. Quote its approved lines rather than
paraphrasing them; they were worded and cleared on purpose.

### 2. Pick the angle

Lead with whatever the brand's own message hierarchy puts first — the job the product does end to
end, not a secondary feature. A supporting capability is proof, not the headline.

### 3. Write the headline

Prefer an approved line from the Knowledge page verbatim. Otherwise write one that names something
real and specific, not a generic claim.

If the Brand Kit's `accentPhraseRequired` is true, mark exactly one phrase per headline with
asterisks: `Every saved link, *finally usable*.` Never zero, never two — `create_creative` and
`update_creative` refuse a headline that gets this wrong, naming the rule.

Keep it short — checked at thumbnail size, not read up close.

### 4. Obey the brand's copy rules

Every rule in `copyRules` is data (a pattern, a message, and which fields it checks), not something
to remember — the backend enforces it on every write and hands back the rule's own message on a
violation, listing every rule the copy tripped. Fix the copy and retry rather than working around
it. A kit with no rules configured is not a bug; it simply never fails a save on that account.

### 5. Choose the photo and look at it

Call `list_creatives`/an existing photo library read, or check what photos are already on hand
before uploading a new one. A photo needs `checked: true` and not `blocked` before a Creative can
go to `READY` — see the photo library's own listing for that state.

A new photo goes through `upload_creative_photo` (local path or URL), which requires `source` (a
URL or a short provenance note, e.g. "Generated with a named model on a given date") and `licence`
(e.g. "Own work", "Unsplash Licence", "Generated, house use"). This closes a real legal exposure: an
unattributed or unlicensed photo running as paid or organic media.

**Open the photo and look at it before using it.** A picture can break every copy rule with text,
a logo or a claim burned into its pixels, and no automated check can see that — this is the first
of this skill's silent failure modes (the full list is below). A human or a prior pass marks a bad
photo `blocked`; if you notice one that should be, say so rather than using it anyway.

Match the crop to the subject: a layout that lays copy over the photo (e.g. `bleed`) puts a face in
the top third in collision with the headline — prefer a layout that keeps the photo and copy on
separate panels (e.g. `stacked`) for those.

### 6. Create, then render

`create_creative` with the Brand Kit (if not the default), layout, theme, photo, headline, body,
caption and alt text. To test a headline variant of an existing concept, pass `variantOf` with that
Creative's id instead of starting fresh — it inherits everything else and gets the next letter.
Then call `get_creative` to verify what was actually stored.

`render_creative` (previewOnly: true first, for a quick contact sheet) runs the render locally and
returns the render id, state, frame URLs and any warnings. Then `preview_creative` to actually look
at it — it returns the contact sheet as an image directly, not just a URL to trust.

### 7. Look at the result honestly

**A successful render proves the frame is structurally correct, not that the ad is good.** Its
in-page checks catch text spilling out of a safe zone, a font that failed to load, a photo that
never decoded, or contrast below the accessible minimum — not whether the headline reads at a
glance, whether the accent phrase falls on the words that carry the meaning, or whether the copy is
sitting on the subject's face. Judge those yourself from the image `preview_creative` hands back. If
the copy collides with the photo, switch layout (see step 5) and re-render rather than shrinking
text until it technically fits.

### 8. Check readiness before calling it done

`get_creative` includes a `readiness` block: caption, alt text, the photo's checked/blocked state
and provenance, and the Creative's own state (not `DRAFT`) are blocking; an AI-generation disclosure
is informational only. Do not call a Creative done while `ready` is false.

### 9. Render for real, then hand off

Once the preview looks right, `render_creative` again with `previewOnly: false` (or omitted) for the
full-size placement frames, then `attach_creative_to_post` with the Post's Work Item id — it maps
each placement to the right destinations (9:16 to TikTok/Reels, 4:5 to Instagram feed, 1:1 to
Facebook, story to story targets) for every destination that has not set its own custom media, and
never touches one that has. Verify with `get_post_status`. From here, use the `conductor-publisher`
skill for scheduling, review and publishing — this skill's job ends at attaching the artwork.

## Making a sequence instead of a single ad

For a platform surface that's tapped or swiped through, set `sequenceKind` to `story` (2 to 7
beats, aim for 3 to 5) or `carousel` (2 to 10 cards, 5 to 7 performs best) on `create_creative` or
`update_creative`, with a `sequence` array. Each beat inherits the Creative and overrides only its
own `headline`, and optionally `body`, `photoId` or `cta`. Structure a story hook, mechanism,
payoff — the first beat has to earn the next tap on its own, since that's where the drop-off is
worst. A logo lockup and CTA claim land on the last beat only by default; `cta: true` on an earlier
beat overrides that. A carousel renders every card at one shared aspect ratio (`carouselRatio`),
since the destination platforms require that.

## Video you already have

Not every video needs the brand layout above — a finished clip (a demo recording, an existing edit,
something a person or another tool already produced) can go up as-is. That's a CLIP Creative: no
photo, headline or layout, just the video.

1. `upload_creative_media` (local path or URL) — same provenance fields as a photo (`source`,
   `licence`, `aiGenerated`). It detects video vs. audio vs. photo automatically and, for a video,
   extracts a poster frame. Use the returned media's id below.
2. `create_creative` with `kind: "CLIP"` and `clipMedia`. One file for every placement:
   `{"default": mediaId}`. A different cut per placement (e.g. a square edit for feed, a vertical one
   for Reels): `{"9x16": mediaId, "4x5": mediaId}` — `default` still covers any placement without its
   own entry. A reel takes exactly one video, so don't split a reel-eligible placement across two.
3. `render_creative` — instant for a CLIP (the backend assembles the frames; nothing renders locally
   here the way a STILL creative's browser-driven render does).
4. `preview_creative` — returns a small poster sheet across placements instead of the STILL contact
   sheet, plus each placement's duration. Still worth a look: a poster is one frame, not the whole
   cut, so open the actual video before calling a rough or misframed clip done.
5. `attach_creative_to_post`, same as any other Creative.

Mind each destination's own duration limit (per docs/publishing.md) — a clip that's fine on one
platform may get rejected or trimmed on another; check before attaching to a Post that fans out
broadly.

## Failures that are silent (carried over from this skill's predecessor)

Automated checks are real but incomplete. These are the ones worth remembering to look for by eye:

- **A photo with text, a logo or a claim burned into its pixels** passes every copy-rule and
  render check, because none of them look at pixels for meaning — only step 5's "open and look at
  it" catches this.
- **A structurally valid render is not necessarily a good one.** The render's own assertions check
  spill, safe zones, fonts and contrast — never whether the headline reads at a glance or the copy
  sits on the subject's face. Step 7 exists because of this gap.
- **A Brand Kit with no copy rules, or no accent-phrase requirement, never fails a save on that
  account.** That is permissive by design, not a bug — but it means the model, not the platform, is
  the only check on brand voice for that workspace until someone configures rules.
- **A stale write is refused, not silently overwritten.** `update_creative` requires the Creative's
  current `version`; a mismatch comes back as a 409 naming the conflict rather than clobbering a
  concurrent edit — reread with `get_creative` and reapply, don't retry blind.

## Related skills

- `conductor-publisher`: schedules and tracks the Post this Creative's artwork attaches to, through
  review and out to each destination. This skill hands off to it, never the reverse.
