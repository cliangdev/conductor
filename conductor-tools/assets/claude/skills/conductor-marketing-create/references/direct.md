# Direct mode: making a Creative from what is in hand

Read when the person already has the headline, photo, clip or finished images (or wants a lettered
headline variant). The shared rules in `SKILL.md` (brand kit first, draft first, no Post) apply.

The copy and the visual language both come from things that already exist for this workspace: a
Brand Kit (data: tokens, copy rules, approved lines) and a Knowledge page (prose: positioning, voice,
facts). Read them rather than inventing. Rendering happens locally through this MCP server with a
browser on this machine; a preview typically finishes in well under a minute.

## What to make

If the request does not already say, ask once with `AskUserQuestion` (single select), then gather
only what the choice needs: one or two follow-up questions, not a form.

- **Static ad** (photo + headline in the brand layout, at every enabled placement; not for organic
  posts). Needs a headline (or draft one from the Knowledge page) and a photo: call
  `list_creative_media({kind: "IMAGE"})` first and offer what is on hand before asking for a new one.
  Nothing fits and none is offered? Offer `generate_creative_image` (step 5). A new photo is a local
  file path (a URL: download it first); it is only uploaded after the person approves the preview.
- **Short video from a photo** (animated text over a still or a moving background): same inputs as a
  static ad; see "Short videos from a photo or clip".
- **Video from a clip I have** (brand layout animated over an existing video) and **post an existing
  video as-is** (no layout): call `list_creative_media({kind: "VIDEO"})` first; if nothing fits, ask
  for a local path (a URL: download it first). A clip used as a MOTION background is previewed from
  the local file too.
- **Organic post or slideshow** (a TikTok or Instagram photo post that is not an ad): skip the brand
  layouts and follow the slideshow path below. Ask for the topic, the photos and a real app
  screenshot if there is one.
- **Headline variant** of an existing Creative: ask which one (id or display id like `12a`) and the
  new headline; flow in step 6.

## Which path

- **An organic post or slideshow** (TikTok or Instagram photo post, no paid placement): build native
  slides with `compose_slideshow`, then post them as a CAROUSEL Creative. Read
  `references/slideshow-formats.md` first; it covers the spec, archetypes, text rules and the flow.
  No logo, badge or offer goes on the slides, and the brand layouts below do not apply.
- **An ad** (a paid placement, or art that must carry the brand lockup and CTA): use the brand
  layouts (STILL) as described below.
- **A video**, a **clip** or **finished images**: see their sections below.

## How to make a Creative

### 1. Read the brand

Pick the kit as in SKILL.md "Brand kit first": `get_brand_kit` with `kitId` if the person named a
brand; otherwise with no `kitId`, and if `otherKits` is non-empty ask once which kit to use. Remember
the chosen kit's id and pass it as `brandKitId` on every `preview_creative_draft` and
`create_creative` call. The kit gives you the colour tokens, font, logo/wordmark/badge
URLs, the CTA claim, the copy rules (each a regex, a message, and which fields it applies to), the
approved lines, the enabled placements, and `knowledgePagePath` — the wiki page holding this
brand's positioning, message hierarchy, approved verbatim lines and voice in prose. Read that page
with `read_knowledge_pages` before writing anything. Quote its approved lines rather than
paraphrasing them when you use one; they were worded and cleared on purpose, and are allowed, not
required. A CTA or offer line (a trial, price, plan or discount) is opt-in per request, never a default. Copy rules with ids starting
`banned-` are lines, phrases or visuals the person rejected earlier: never reuse them. When the person
rejects one now, ask once whether to ban it; on a yes append a `banned-<slug>` rule (escaped regex
`pattern`, `flags: "i"`, `fields` headline, body and caption, a `message` naming it as rejected and
the date) to the full existing `copyRules` list, call `update_brand_kit` (it replaces the whole list),
and verify with `get_brand_kit`.

**If `configured` comes back `false`**, this kit has never been touched — a brand-free stock
default, not this workspace's actual brand. Set it up before writing any ad rather than rendering
against placeholder tokens: gather colours, font, CTA claim and any copy rules or approved lines
(from a website via `WebFetch`, a brand doc via `Read`, or by asking directly), then call
`update_brand_kit` for the data fields and `upload_brand_image` for the mark/wordmark/badge. Confirm
with `get_brand_kit` afterward — `configured` should now read `true`. (`references/brand-kit-setup.md`
walks a person through exactly this.)

### 2. Pick the angle

Lead with whatever the brand's own message hierarchy puts first — the job the product does end to
end, not a secondary feature. A supporting capability is proof, not the headline.

Then start from a concept: one visual idea, plus the format that carries it. Not "a headline on a
photo". Decide what the viewer should see happening (the product doing its job, or a concrete scene
of the problem or the result) before you write a line of copy.

### 3. Write the headline

Prefer an approved line from the Knowledge page verbatim. Otherwise write one that names something
real and specific, not a generic claim. Write 6 to 10 candidates across hook types, score them and
keep the best two or three: `references/hooks.md`. Every candidate must read like a person posted
it: `references/voice.md`.

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

**Nothing in the library fits, or the person asks for a generated one?** Call
`generate_creative_image` with a scene prompt drawn from the brand's Knowledge page and the angle:
the scene, subject, light, framing and where the headline's negative space sits, written by the
prompt-writing rules in `references/realism-check.md`. The tool adds its own
fixed rules (photographic, no text, letters, logos, UI or phone screens). Generate with Codex by
default: pass `useCodex: true` without asking, since it is cheaper than the API (it runs the person's
local Codex CLI on their ChatGPT plan allowance, about a minute an image). If Codex fails (missing, logged
out, or a plan without image generation), say why and call again without `useCodex`: that uses the OpenAI
Images API when a key is set (`conductor config set-openai-api-key`; 20-45 s; larger sizes cost more). With
no key it returns `CODEX_CONFIRMATION_REQUIRED`: ask whether to fix Codex (`codex login`), set a key, or
skip and use a library photo. `conductor doctor` reports both routes. Quality defaults to `high` (both routes); pass `quality: "medium"` or `"low"` only for throwaway drafts, and Codex runs on its cheapest model. It returns the first image plus `files[].path`; pass a path as `photoPath` (or
`sequence[].photoPath`). When the real product must appear, list a real shot in
`referenceImagePaths` — never describe an invented product. Each file gets a provenance sidecar
(`aiGenerated: true`) that the preview and commit use automatically, so don't ask about it, and the
AI label applies. `aspect` defaults to `"9:16"` (1440x2560, the largest 9:16 size); `"4:5"`, `"1:1"`,
`"2:3"`, `"3:2"` and `"16:9"` are the other options and `size` sets an exact `WIDTHxHEIGHT`. Generate in the
aspect of the placement you will use it full-bleed in. The result's `placementFit` gives each placement's
scale (9:16, 4:5, 1:1, and with the video zoom). Never use a photo above 1.0x full-bleed: a 1200x1200
photo at 9:16 is a 1.6x upscale and a 1024x1536 one is 1.25x. Fix it before previewing: generate at the
largest size in that aspect, use a different photo, or pick a layout that shows the photo framed at or
below native size (`references/layout-guide.md`, "Resolution").

**The photo shows the product doing its job**, or a concrete scene of the problem or the result: a real
screen or shot (pass it in `referenceImagePaths`, or use a library photo of the product in use). Stock
poses (a person smiling at a phone on a couch, a mug, a generic laptop) and empty decorative scenes (a
sunlit counter with nothing happening) are out. A generated still cannot show an invented product
screen; real product shots come in as references. Ask for `count: 2` or `3` when you want options
(each uses Codex allowance, or bills the API key on the fallback route).

**Every generated photo gets the realism check** in `references/realism-check.md` before it is used,
on a 100% crop at each placement's size and crop, never a contact sheet: it must not look AI-made
(waxy surfaces, duplicated objects, warped hands, mushy detail). Library photos are checked too. On a
fail, regenerate with a prompt that names the failed tells, at most twice; if the third still fails,
say what looks generated and offer a library photo, a reference photo or filming. Never ship a FAIL. The check is about quality, not
concealment: the AI label still applies.

**Open the photo and look at it before using it** — a generated one most of all (pseudo-text, warped
hands or faces, a fake product or app screen; the realism check covers the rest). A picture can break every copy rule with text,
a logo or a claim burned into its pixels, and no automated check can see that — this is the first
of this skill's silent failure modes (the full list is in `SKILL.md`). A human or a prior pass marks a bad
photo `blocked`; if you notice one that should be, say so rather than using it anyway.

Match the crop to the subject. A layout that lays copy over the photo (`bleed`) can put a face in
collision with the headline: move the subject with `focalOverride` when the crop has room, pick a
photo with the subject lower, or use `card`, which keeps the photo and copy on separate panels. Pick the layout with `references/layout-guide.md`
(default `bleed` when 9:16 is enabled; never `stacked` or `split` there, and never rely on the
registry default).

### 6. Preview the draft

Preview two or three genuinely different drafts: a different hook, layout or photo, not three crops of
one idea. Show them together and let the person pick. Run the critique in step 7 on each first.

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

**Read `checks` and `passed` before you show anything.** The contact sheet never runs the render's
safety checks, so the preview also runs them for every placement (and, for a video, its end card)
and returns them as `checks: [{placementKey, index?, rule, message, severity}]` plus `passed`. An
`error` check (`safeZone`, `spill`, `contrast`, `font`, `image`, ...) is exactly what would fail the
full render after the person approved: e.g. an App Store badge intruding into the band TikTok/Reels
reserve on 9:16. A `warning` about a soft, upscaled photo means the resolution gate was missed: fix the
photo (step 5) before showing the draft. Other warnings do not block, but mention them.

When `passed` is false, **do not show the image or ask for approval yet.** Fix the design and
preview again, repeating until `passed` is true:

- the copy collides with a reserved zone: shorten the headline or drop the body;
- the photo takes too much of a placement: lower its height there with `layoutOverrides.band`
  (e.g. `{"band": {"9x16": 1100}}`);
- the bottom row (CTA/badge) does not clear the safe zone: raise the clearance with
  `layoutOverrides.padBottom`, or pick a layout that keeps it clear;
- the text is too large or too small for a placement: set it with `typeOverrides` (below).

Tell the person what you changed and why when you do show it. Never offer **Approve & upload**
while a blocking check fails.

**Bigger or smaller text on one placement: use `typeOverrides`, not a different layout.** It pins
the headline per placement as `{"<placementKey>": [fontSize, lineHeight?, letterSpacing?]}`, e.g.
`{"9x16": [96], "1x1": [88]}` for a bigger headline on 9:16 and 1:1. `fontSize` is in artboard px
(a 9:16 board is 1080 wide), `lineHeight` a multiplier (e.g. `1.0`) and `letterSpacing` in px
(usually negative); leave the last two out to take the engine's defaults for that size. A placement
you do not name stays auto-fitted. A pinned size is not shrunk to fit, so re-check `checks` after
setting one. It works on `preview_creative_draft` and `create_creative`/`update_creative`; changing
the headline or layout of a saved Creative clears pinned sizes unless you send them again.

### 7. Look at the result honestly

**Passing checks prove the frame is structurally correct, not that the ad is good.** The checks
(`passed: true`) catch text spilling out of a safe zone, a font that failed to load, a photo that
never decoded, or contrast below the accessible minimum — not whether the headline reads at a
glance, whether the accent phrase falls on the words that carry the meaning, or whether the copy is
sitting on the subject's face.

Score the draft with `references/design-critique.md` before the person sees it: six criteria, 1 to 5,
at phone size. Any score below 3 means fix and re-render, at most twice; then show the best draft with
one honest line on what is still weak. Show the scores in one compact line with the preview. If the
copy collides with the photo, change the layout or `focalOverride` (step 5) rather than shrinking text
until it technically fits.

Only once `passed` is true, show the image to the person and ask with `AskUserQuestion` —
**Approve & upload**, **Request changes**, **Stop**. If `passed` is false you are not at this step
yet: fix it and re-preview first (step 6). On changes, preview again with the edits (another draft; nothing was saved). On
Stop, leave it: the draft stays on this machine under `.conductor/drafts/` and is cleaned up
automatically once it is committed or two weeks old. Say in the question that approving
also confirms the photo carries no burned-in text, logo or claim. Only on **Approve & upload**, call
`commit_creative_draft({draftDir, photoChecked: true})` with the `draftDir` the approved preview
returned, plus provenance (`source`, `licence`, `aiGenerated`) only for local files the preview
lists in `provenance.needed`. Pass `photoChecked` only after that approval, never on your own
judgement. Show the one-line realism verdict for each generated photo with the preview.

### 8. Check readiness before calling it done

The preview already lists the readiness gaps (including a local file's sidecar provenance), so fix
what you can (caption, alt text) before asking for approval; the photo check is cleared by the
approval itself (`photoChecked: true` on the commit, for files that commit uploads; a library photo
keeps its own verdict). After the commit, `get_creative` includes a `readiness` block: caption, alt
text, the photo's checked/blocked state and provenance, and the Creative's own state (not `DRAFT`)
are blocking; an AI-generation disclosure is informational only (when publishing, turn on the
platform's AI-generated label). If the only blocking item left is `state`, call
`update_creative({creativeId, state: "READY"})` and verify with `get_creative`; the backend refuses
READY while anything else blocking fails. Otherwise name exactly what is missing and ask. Do not
call a Creative done while `ready` is false.

### 9. Commit, then hand off

`commit_creative_draft` uploads the draft's local files, creates the Creative (or updates
`baseCreativeId`'s), and runs the full-size render in one call, then returns the Creative id and
display id, the frames and the readiness. It can be run once per draft; if its render fails the
Creative is already saved — call `render_creative`, don't commit again. Verify with `get_creative`.
Then `attach_creative_to_post` with the Post's Work Item id — it maps each placement to the right
destinations (9:16 to TikTok/Reels, 4:5 to Instagram feed, 1:1 to Facebook, story to story targets)
for every destination that has not set its own custom media, and never touches one that has. Verify
with `get_post_status`. From here, use the `conductor-marketing-publish` skill for scheduling, review and
publishing — this skill's job ends at attaching the artwork. If there is no Post yet, say so plainly
and name the next step (creating one with `conductor-marketing-publish`) rather than leaving the artwork with
no stated destination.

When the Creative came from a team run, or the person points to a publish pack, look for
`.conductor/content-runs/*/publish-pack.json` whose `creativeId` matches. It carries per-destination
captions and publish options (disclosure, AI label, cover frame, alt text) and a pinned comment. When
you go on to create or edit the Post with `conductor-marketing-publish`, pre-fill each target's
`captionOverride` and options from it, show them in the confirmation table and let the person change
them. Mention the pinned comment (and, for a post that promotes an app, the Stories repost with a link
sticker) as manual steps, since Conductor cannot post comments. The Post still goes through the normal
review.

## Making a sequence instead of a single ad

For a platform surface that's tapped or swiped through, set `sequenceKind` to `story` (2 to 7
beats, aim for 3 to 5) or `carousel` (2 to 10 cards, 5 to 7 performs best) on
`preview_creative_draft` (then `commit_creative_draft`), with a `sequence` array. Each beat inherits
the Creative and overrides only its own `headline`, and optionally `body`, `photoPath` (a local
photo, uploaded on commit) or `photoId`, or `cta`. Structure a story hook, mechanism,
payoff — the first beat has to earn the next tap on its own, since that's where the drop-off is
worst. A logo lockup and CTA claim land on the last beat only by default; `cta: true` on an earlier
beat overrides that. Set `cta: false` on every beat unless the request opts in to a CTA. A story sequence publishes: attach it to an Instagram or Facebook Story
destination and each beat goes out as its own story, in order. A carousel renders every card at one shared aspect ratio (`carouselRatio`),
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
3. `render_creative` — instant for a CLIP that keeps its own sound (the backend assembles the frames;
   nothing renders in a browser). With `audio` (below) it encodes a new MP4 on this machine with
   ffmpeg instead.
4. `preview_creative` — returns a small poster sheet across placements instead of the STILL contact
   sheet, plus each placement's duration. Still worth a look: a poster is one frame, not the whole
   cut, so open the actual video before calling a rough or misframed clip done.
5. `attach_creative_to_post`, same as any other Creative.

Music on a CLIP is `audio` on the Creative (see "Music" below): `source: "clip"` keeps the clip's
own sound (the default), `"track"` replaces it with a library track, `"mix"` plays the track under
the clip's sound (`volume` defaults to 0.3 and the music ducks under the clip's sound; the clip must have sound,
or `mix` is refused), `"none"` mutes it. Set it on `create_creative`/`update_creative`, then
`render_creative`; look at the new MP4 before attaching, since a poster is one frame.

Mind each destination's own duration limit (per docs/publishing.md) — a clip that's fine on one
platform may get rejected or trimmed on another; check before attaching to a Post that fans out
broadly.

## Finished images (carousel or single still)

Slides made by `compose_slideshow` take this same CAROUSEL route: pass its `slidePaths` to
`preview_creative_draft` (see `references/slideshow-formats.md`).

When the slides are already designed (Figma exports, your own renderer, device-framed screenshots)
there is nothing for the brand layout to do, so use a CAROUSEL Creative: finished images posted
as-is. A one-slide CAROUSEL is a finished still. For text on a photo that Conductor should lay out,
use the STILL flow above instead (its "carousel" `sequence` is brand-rendered cards, a different
thing).

1. Get the images into the media library. A set in a folder goes up in one call:
   `upload_creative_media({folder, glob?, labelTemplate: "01-import {stem}"})` (or `files: [...]`),
   up to 100, in name order, with the shared `source`/`licence`/`aiGenerated`. Every file gets its own
   result, so retry only the ones with an `error`. Find an already uploaded set with
   `list_creative_media({labelPrefix: "01-import"})` rather than uploading again. `label`, `source`
   and `licence` can't exceed 200 characters.
2. Local files: `preview_creative_draft({kind: "CAROUSEL", caption, slidePaths: {"4x5": [path, ...]}})`
   returns a contact sheet per placement (slides in order), readiness and any violations; look at it
   with the person, then `commit_creative_draft` (it uploads in order and requests the render).
   Media already in the library: `create_creative({kind: "CAROUSEL", slides: {"4x5": [mediaId, ...]}, slideAltText, caption})`
   and then `render_creative` (instant, server-side).
3. Placements. `slides` is a list per placement key (`4x5`, `9x16`, `1x1`); `default` is the
   fallback for a placement with no list of its own. Each image must match its placement's aspect
   within 2% (4x5 = 1080x1350, 9x16 = 1080x1920, 1x1 = 1080x1080); a mismatch comes back as a
   `slideAspect` violation, so fix the image rather than the list. Limits: 10 slides for 4x5 and
   1x1, 35 for 9x16 (TikTok photo posts); aim for 5 to 7.
   With a music track, `audio: {source: "track", trackId}` plus `audioPath` for a file not yet in the
   library (see "Music" below) also renders a 9:16 slideshow MP4 here: `preview_creative_draft`
   returns it as `slideshowVideoPath` beside the contact sheet, so watch it with the person, not
   just the sheet. `slideshow: {slideDurations, zoom}` sets the pace (one entry per 9x16 slide, 1 to
   10 s; default 3 s for the first slide and 2.5 s for the rest) and the slow push-in (default on).
   Keep the total short: a 20-slide default runs about 40 s.
4. Alt text: `slideAltText` has one entry per slide, in order (or a single Creative `altText`); readiness
   blocks without it. Slides are never rewritten, so check them for burned-in text, logos and claims
   and approve with `photoChecked: true`.
5. `get_creative` for `readiness` (slides, caption, altText, photoChecked block going READY), then
   `attach_creative_to_post` (it renders first if needed). The mapping: Instagram feed gets `4x5`
   (else `1x1`), Facebook feed `1x1` (else `4x5`), TikTok `9x16` (else `4x5`). Reels, Stories and
   YouTube can't take a multi-image post; the preflight blocks with `MULTI_IMAGE_UNSUPPORTED`, so
   switch that destination to Feed or remove it. A carousel with a track is different: no platform
   accepts a chosen track on photos, so each destination sends it as `carouselAs: "photos"`
   (silent) or `"video"` (the slideshow MP4 with the track). Attach defaults to video for TikTok
   and YouTube and photos for Instagram and Facebook feed, and writes the choice into the target's
   options; change it with `set_publish_targets` (`conductor-marketing-publish` skill). Mind each platform's length limit
   for the video.

## Short videos from a photo or clip

A MOTION Creative animates the same brand layout from steps 1-5 above into a short video, instead
of a static frame — no separate video tool needed. Use it when a still doesn't hold attention, or a
destination rewards video over an image.

1. Pick a photo (step 5; a generated photo works here too) or, for a moving background, a video — check `list_creative_media({kind:
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
     is one), `"track"` (a library track: `trackId`, plus `volume` and `fadeOutSec`; see "Music"
     below), or `"none"`.

   A MOTION Creative is one animated frame per placement: it cannot also be a story or a carousel
   (`sequenceKind`/`sequence`) — that is refused with a 422 (`motionNoSequence`). For several beats, make
   one MOTION Creative per beat, or a STILL story/carousel.
3. The preview renders three key moments across the timeline into one sheet image (not the finished
   video). Judge it the same honest way as step 7 above — does the copy's entrance finish before the
   next beat, does the end card actually hold long enough to read — and get approval before
   spending a full render on it.
4. `commit_creative_draft` then uploads the media, saves the Creative and runs the local,
   ffmpeg-driven encode — roughly 20 seconds per placement for an 8-second video. No ffmpeg on this
   machine fails with a clear message naming how to get one rather than silently producing nothing.
5. `attach_creative_to_post`, same as any Creative — one MP4 per placement.

A reel or other vertical placement takes exactly one video, same as a CLIP.

## Music

Music works on a MOTION (`clip`, `track` or `none`), a CAROUSEL (`track`, which makes a slideshow video) and
a CLIP (`track`, `mix`, `none`), through `audio: {source, trackId, volume, fadeOutSec}`. The rules
are the same for all three:

- Before making a video or slideshow with music, call `list_creative_media({kind: "AUDIO"})` and
  reuse a library track that fits.
- A new music file goes into the library first with `upload_creative_media`, with `source` (where it
  came from) and `licence` (the terms, e.g. "Own work", "Licensed for social use"). Then reference
  it by `trackId`. With `audioPath` the file uploads on commit, so pass `source` and `licence` to
  `commit_creative_draft` (or keep a `.provenance.json` sidecar beside it).
- Readiness blocks the Creative (`audioProvenance`) until the track's `source` and `licence` are
  recorded, for every kind. Use only music the person has the right to use on the destination
  platforms.
- Never bake music into an MP4 outside Conductor when `audio` can do it: the track then never reaches
  the library, its licence is unrecorded, and the Creative can't change or drop it later.
- Volume defaults: 0.8 for MOTION, 1 for a CAROUSEL or a CLIP `track`, 0.3 for `mix`. A CAROUSEL or CLIP encode
  normalises loudness (MOTION does not); if the level is still wrong, adjust `volume` rather than re-exporting.
- Rendering runs on this machine and needs ffmpeg; without it the tool fails with a clear message.

## Cleaning up

`delete_creative` and `delete_media` are permanent. Never call them unless the person asked or confirmed that exact item; the server refuses a Creative still on a Post or with other variants, and Media still in use.
