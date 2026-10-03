---
name: conductor-creative
description: Creates on-brand marketing creative through the Conductor MCP tools — static ads and short videos alike, rendered locally on this machine as a draft, checked by eye and approved before anything is saved to Conductor. Covers a photo-plus-headline ad at every enabled placement (e.g. TikTok/Reels 9:16, Instagram feed 4:5, Facebook 1:1); a short video made from a still photo or an existing clip (animated text, zoom/pan, background music, a TikTok/Reels/Shorts-ready cut); posting a finished video as-is; a story or carousel sequence; a lettered headline/hook variant for testing; and, when a workspace's Brand Kit has never been set up, setting one up first (colours, font, logo, copy rules) from a website, a brand doc, or a few direct answers. Use when asked to make, design, write or export a social ad, a promo image, a short-form video, an animated ad, or to configure/update a project's brand.
user-invocable: true
allowed-tools: mcp__conductor__*, AskUserQuestion, Read, Glob, Grep, WebFetch
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
separate render service to wait on. A preview typically finishes in well under a minute.

**Draft first, save after approval.** Every Creative here goes draft, look, approve, commit:
`preview_creative_draft` renders the artwork on this machine and writes **nothing** to Conductor — no
Creative, no upload — then the person looks at it, and only on their approval does
`commit_creative_draft` upload the media, save the Creative and do the full render. Never commit a
draft the person has not seen and approved.

## How to make a Creative

### 1. Read the brand

Call `get_brand_kit` (a project may have more than one — pass `kitId` if the person named a
specific brand, otherwise the default). It gives you the colour tokens, font, logo/wordmark/badge
URLs, the CTA claim, the copy rules (each a regex, a message, and which fields it applies to), the
approved lines, the enabled placements, and `knowledgePagePath` — the wiki page holding this
brand's positioning, message hierarchy, approved verbatim lines and voice in prose. Read that page
with `read_knowledge_pages` before writing anything. Quote its approved lines rather than
paraphrasing them; they were worded and cleared on purpose.

**If `configured` comes back `false`**, this kit has never been touched — a brand-free stock
default, not this workspace's actual brand. Set it up before writing any ad rather than rendering
against placeholder tokens: gather colours, font, CTA claim and any copy rules or approved lines
(from a website via `WebFetch`, a brand doc via `Read`, or by asking directly), then call
`update_brand_kit` for the data fields and `upload_brand_image` for the mark/wordmark/badge. Confirm
with `get_brand_kit` afterward — `configured` should now read `true`. (`/conductor:creative` walks
a person through exactly this when invoked with no brand set up yet, if you'd rather hand it off.)

### 2. Pick the angle

Lead with whatever the brand's own message hierarchy puts first — the job the product does end to
end, not a secondary feature. A supporting capability is proof, not the headline.

### 3. Write the headline

Prefer an approved line from the Knowledge page verbatim. Otherwise write one that names something
real and specific, not a generic claim.

If the Brand Kit's `accentPhraseRequired` is true, mark exactly one phrase per headline with
asterisks: `Every saved link, *finally usable*.` Never zero, never two — `preview_creative_draft`,
`create_creative` and `update_creative` refuse a headline that gets this wrong, naming the rule.

Keep it short — checked at thumbnail size, not read up close.

### 4. Obey the brand's copy rules

Every rule in `copyRules` is data (a pattern, a message, and which fields it checks), not something
to remember — the backend enforces it on every write and hands back the rule's own message on a
violation, listing every rule the copy tripped. Fix the copy and retry rather than working around
it. A kit with no rules configured is not a bug; it simply never fails a save on that account.

### 5. Choose the photo and look at it

Call `list_creative_media` (optionally `kind: "IMAGE"`) to see what's already on hand before
uploading a new one — id, dimensions, checked/blocked state and provenance for everything already in
the project's media library. A photo needs `checked: true` and not `blocked` before a Creative can
go to `READY`.

A photo that is not in the library yet is previewed straight from its local file (`photoPath` on
`preview_creative_draft`; a URL is downloaded to a local file first) and is only uploaded when the
draft is committed — pass `source` (a URL or a short provenance note, e.g. "Generated with a named
model on a given date") and `licence` (e.g. "Own work", "Unsplash Licence", "Generated, house use")
to `commit_creative_draft`, which records them on every file it uploads. This closes a real legal
exposure: an unattributed or unlicensed photo running as paid or organic media. A photo already in
the library is used by its id (`photoId`) with no upload at all.

**Open the photo and look at it before using it.** A picture can break every copy rule with text,
a logo or a claim burned into its pixels, and no automated check can see that — this is the first
of this skill's silent failure modes (the full list is below). A human or a prior pass marks a bad
photo `blocked`; if you notice one that should be, say so rather than using it anyway.

Match the crop to the subject: a layout that lays copy over the photo (e.g. `bleed`) puts a face in
the top third in collision with the headline — prefer a layout that keeps the photo and copy on
separate panels (e.g. `stacked`) for those.

### 6. Preview the draft

`preview_creative_draft` with the Brand Kit (if not the default), layout, theme, photo (`photoPath`
for a local file, `photoId` for one already in the library), headline, body, caption and alt text.
It renders a contact sheet on this machine and returns it as an image, with the `draftDir` it wrote
to (`.conductor/drafts/<timestamp>-<slug>/`), the readiness gaps and any render warnings. **Nothing
has been saved to Conductor.** A copy-rule or accent-phrase violation comes back as an error naming
the rule and field, without rendering — fix the copy and preview again.

To change an existing Creative's copy, layout or photo, preview the edit first with `baseCreativeId`:
only the fields you pass change, and the commit then updates that Creative instead of creating one.

To test a headline variant of an existing concept, preview the new headline the same way
(`baseCreativeId` plus `headline`) so the person sees it on the artwork — but do **not** commit that
draft, since a commit would update the original. Once they approve, call `create_creative` with
`variantOf` and the headline instead: it inherits everything else and gets the next letter.

Pass `full: true` to render every placement at full size instead of the contact sheet (slower; the
image returned is the first placement).

### 7. Look at the result honestly

**A successful render proves the frame is structurally correct, not that the ad is good.** Its
in-page checks catch text spilling out of a safe zone, a font that failed to load, a photo that
never decoded, or contrast below the accessible minimum — not whether the headline reads at a
glance, whether the accent phrase falls on the words that carry the meaning, or whether the copy is
sitting on the subject's face. Judge those yourself from the image `preview_creative_draft` hands
back. If the copy collides with the photo, switch layout (see step 5) and preview again rather than
shrinking text until it technically fits.

Then show the image to the person and ask with `AskUserQuestion` — **Approve & upload**, **Request
changes**, **Stop**. On changes, preview again with the edits (another draft; nothing was saved). On
Stop, leave it: the draft stays on this machine under `.conductor/drafts/` and is cleaned up
automatically once it is committed or two weeks old. Only on **Approve & upload**, call
`commit_creative_draft({draftDir})` with the `draftDir` the approved preview returned.

### 8. Check readiness before calling it done

The preview already lists the readiness gaps, so fix what you can (caption, alt text, a checked
photo) before asking for approval. After the commit, `get_creative` includes a `readiness` block: caption, alt text, the photo's checked/blocked state
and provenance, and the Creative's own state (not `DRAFT`) are blocking; an AI-generation disclosure
is informational only. Do not call a Creative done while `ready` is false.

### 9. Commit, then hand off

`commit_creative_draft` uploads the draft's local files, creates the Creative (or updates
`baseCreativeId`'s), and runs the full-size render in one call, then returns the Creative id and
display id, the frames and the readiness. It can be run once per draft; if its render fails the
Creative is already saved — call `render_creative`, don't commit again. Verify with `get_creative`.
Then `attach_creative_to_post` with the Post's Work Item id — it maps each placement to the right
destinations (9:16 to TikTok/Reels, 4:5 to Instagram feed, 1:1 to Facebook, story to story targets)
for every destination that has not set its own custom media, and never touches one that has. Verify
with `get_post_status`. From here, use the `conductor-publisher` skill for scheduling, review and
publishing — this skill's job ends at attaching the artwork.

## Making a sequence instead of a single ad

For a platform surface that's tapped or swiped through, set `sequenceKind` to `story` (2 to 7
beats, aim for 3 to 5) or `carousel` (2 to 10 cards, 5 to 7 performs best) on
`preview_creative_draft` (then `commit_creative_draft`), with a `sequence` array. Each beat inherits
the Creative and overrides only its own `headline`, and optionally `body`, `photoPath` (a local
photo, uploaded on commit) or `photoId`, or `cta`. Structure a story hook, mechanism,
payoff — the first beat has to earn the next tap on its own, since that's where the drop-off is
worst. A logo lockup and CTA claim land on the last beat only by default; `cta: true` on an earlier
beat overrides that. A carousel renders every card at one shared aspect ratio (`carouselRatio`),
since the destination platforms require that.

## Video you already have

Not every video needs the brand layout above — a finished clip (a demo recording, an existing edit,
something a person or another tool already produced) can go up as-is. That's a CLIP Creative: no
photo, headline or layout, just the video.

A CLIP has no artwork to render, so it is the one flow without a draft preview: open the video
itself and check it with the person before uploading.

1. Check `list_creative_media({kind: "VIDEO"})` for a clip already in the project before uploading a
   new one. A new one goes through `upload_creative_media` (local path or URL) — same provenance
   fields as a photo (`source`, `licence`, `aiGenerated`). It detects video vs. audio vs. photo
   automatically and, for a video, extracts a poster frame. Use the media's id below either way.
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

## Short videos from a photo or clip

A MOTION Creative animates the same brand layout from steps 1-5 above into a short video, instead
of a static frame — no separate video tool needed. Use it when a still doesn't hold attention, or a
destination rewards video over an image.

1. Pick a photo (step 5) or, for a moving background, a video — check `list_creative_media({kind:
   "VIDEO"})` first and use its media id if something fits; otherwise pass the local file as
   `clipPath` (previewed from disk, uploaded on commit). For background music,
   `list_creative_media({kind: "AUDIO"})` the same way, else `audioPath`.
2. `preview_creative_draft` with `kind: "MOTION"` (to change a saved MOTION Creative, `baseCreativeId`
   instead) plus:
   - `motion.preset` — how the copy enters: `fade-up` (default), `word-by-word`, `accent-pop`, or
     `none` (everything visible from the first frame).
   - `motion.durationSec` — 3 to 60, default 8.
   - `motion.background` — `source: "photo"` (default; `motion: "zoom-in"` (default), `zoom-out`,
     `pan-left`, `pan-right`, or `none`) or `source: "clip"` with `clipMediaId` (from step 1) and
     `clipStartSec`.
   - `motion.endCard` — default true: the last 2 seconds hold the finished composition, CTA
     included, regardless of how short the preset's own entrance would otherwise leave it.
   - `audio` — `source: "clip"` (the background clip's own recorded sound — the default when there
     is one), `"track"` (a separate library track: `trackId` from `upload_creative_media`, plus
     `volume` and `fadeOutSec`), or `"none"`.
3. The preview renders three key moments across the timeline into one sheet image (not the finished
   video). Judge it the same honest way as step 7 above — does the copy's entrance finish before the
   next beat, does the end card actually hold long enough to read — and get approval before
   spending a full render on it.
4. `commit_creative_draft` then uploads the media, saves the Creative and runs the local,
   ffmpeg-driven encode — roughly 20 seconds per placement for an 8-second video. No ffmpeg on this
   machine fails with a clear message naming how to get one rather than silently producing nothing.
5. `attach_creative_to_post`, same as any Creative — one MP4 per placement.

A library music track's `licence` has to be recorded on upload (same as a photo's provenance) — the
readiness checklist blocks a MOTION Creative on it whenever `audio.source` is `"track"`. A reel or
other vertical placement takes exactly one video, same as a CLIP.

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
- **A draft is not a saved Creative.** `preview_creative_draft` writes nothing to Conductor, so a
  Creative or media id does not exist until `commit_creative_draft` runs — don't hand a draft's
  folder to `attach_creative_to_post` or a Post.
- **A stale write is refused, not silently overwritten.** `update_creative` requires the Creative's
  current `version`; a mismatch comes back as a 409 naming the conflict rather than clobbering a
  concurrent edit — reread with `get_creative` and reapply, don't retry blind.

## Related skills

- `conductor-publisher`: schedules and tracks the Post this Creative's artwork attaches to, through
  review and out to each destination. This skill hands off to it, never the reverse.
