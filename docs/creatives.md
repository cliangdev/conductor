# Creatives

How Conductor turns a brand kit and a photo into upload-ready frames on a Post: a brand-agnostic
concept → render → upload flow (COND-24).

## Brand Kits

A **Brand Kit** (`brand_kit`, `com.conductor.creative.BrandKit`) is per-project brand truth: colour
tokens, font, logo/wordmark/badge object paths, a CTA claim, copy rules (regex + message + fields +
exception pattern), an accent-phrase requirement, approved lines and the enabled placements a Creative
renders at by default. A project may hold several kits; exactly one is `isDefault`. With several kits, the marketing create skill asks which one to use (it reads the others from `get_brand_kit`'s `otherKits`) and passes it as `brandKitId`. Nothing brand-specific
ever lives in product code — every rule a Creative is checked against is data on the kit it renders with.
A kit's accent phrase always renders in italic, so its `fontUrl` must include that font's italic face
(for Google Fonts, the `ital` axis, e.g. `family=Poppins:ital,wght@0,400;0,800;1,800`) — without it the
browser fakes the slant and the space after the accent phrase visually closes up.

## Creatives

A **Creative** (`creative`, `com.conductor.creative.Creative`) is a library object under the Marketing
Area — not a Work Item. It carries a photo, headline, body, caption, alt text, a layout and theme, an
optional story/carousel `sequence`, and opt-in `placements` beyond the kit's defaults. `number` +
`variantLetter` form the display id (`12a`, `12b`); `version` is JPA's optimistic-lock column, giving
a 409 on a stale write.

`lockup` (`plain`, the default, or `chip` — the logo lockup on a white pill, for busy photography) and
`layoutOverrides` (`{band?, padBottom?}`, each a per-placement pixel override of the stacked layout's
photo band height / the 9x16 panel's bottom safe-zone clearance) are the two per-concept
layout settings; `CreativeValidator` checks every `layoutOverrides` placement key against the
registry and a sane pixel bound, and `conductor-creative/render.js` applies them at render time.

`CreativeValidator` (`com.conductor.creative.CreativeValidator`) is data-driven: layout/placement keys
must be real, the layout must support the requested
theme, the kit's accent-phrase and copy rules apply to every text field (including each sequence beat),
sequence bounds are enforced (a story is 2–7 beats, a carousel 2–10 cards, every beat after the first
needs its own headline), and every referenced photo must exist in the project. A separate, non-blocking
**readiness** read (`CreativeService.readiness`) checks caption, alt text, the photo's checked/provenance
verdicts and AI disclosure (a local file's sidecar provenance counts in the preview too) — useful for a pre-publish checklist, but never a reason to refuse a write.

Approving a draft is also the photo check: `commit_creative_draft({photoChecked: true})` records that the person
saw no burned-in text, logo or claim in the files that commit uploads (a library photo keeps its own verdict).
A Creative then leaves DRAFT with `update_creative({state: "READY"})`, which is refused while any other blocking
readiness item fails.

Photos (`creative_photo`) carry provenance (source, licence, AI disclosure), a checked/blocked verdict and
per-placement focal points, and go through the same mint → signed PUT → confirm upload shape as a Post's
own file Assets — except a Creative photo has no owning Work Item, so it gets its own storage-backed row.

The placement/layout registry (`CreativeRegistry`, `creative/registry.json`) is the single source of truth
for which layout and placement keys a Creative may use: six placements (`9x16`, `4x5`, `1x1`, `story`,
`1.91x1`, `2x3`) today, four layouts. Adding one is a registry change, never a code change to the
validator or the render mapping below.

## Rendering — local, not a backend job

**Rendering happens on the user's own machine**, never in this backend. A Claude Code (or Claude Desktop)
session runs the render locally through the Conductor MCP server's `render_creative` tool or the CLI's
`conductor creative render <creativeId>`, using Playwright against an installed Chrome/Chromium and the
shared `conductor-creative/job` render core (brand-agnostic).
The web editor's live preview needs no render at all — it re-renders in the browser on every keystroke.

The backend's role is narrow: it hands the local job a **render spec** (the resolved creative + brand
data it needs, with signed photo/logo URLs), and it records what the job did.

1. `POST .../creatives/{creativeId}/renders` creates a `creative_render` row (`RUNNING`) and returns the
   spec — the *only* place `spec` is ever returned. Refuses with 422 (the same shape as a Creative write)
   if the Creative lacks a photo or headline, or fails any T2 structural rule. The resolved `placements`
   the job must produce one frame per are the kit's enabled set unioned with the Creative's own opt-ins,
   intersected with the registry — except a story Creative, which renders only `["story"]`, and a
   carousel, which renders only `[carouselRatio]` with one indexed frame per beat.
2. The job `PUT`s each placement's frame to `.../renders/{renderId}/frames/{placementKey}` as it finishes
   — JPEG (`image/jpeg`, quality 92), since Instagram feed images and TikTok photo posts both refuse PNG
   (`MediaTargetValidator`'s AC-P0-3.3 gate would otherwise block the Post at approval). `sheet` names a
   preview-only contact sheet and stays PNG (`image/png`) — nothing downstream gates it, and PNG keeps a
   text-heavy grid crisp. The endpoint accepts either content type, 415 on anything else; the stored
   frame's extension and `content_type` match what was sent. Only accepted while the render is
   `RUNNING` — 409 once it has settled.
3. `POST .../renders/{renderId}/complete` or `.../fail` settles it. A failure **deletes every frame
   already written** — a failed render leaves nothing for attach to find.
4. There is no scheduler watching a render. A `RUNNING` row a dead local job never finished is only
   noticed — and flipped to `FAILED` "timed out" — the next time anyone reads it (a render older than 30
   minutes on a `GET`).

### Rendering from the web app

Someone using only the web app can still render a Creative that needs a browser or ffmpeg (a still,
MOTION, a CAROUSEL with a music track, a CLIP with track/mix/none audio). The **Render** button calls
`POST .../creatives/{creativeId}/render-runs` (Admin/Creator), which fires the system workflow
`conductor-creative-render` on the workspace's self-hosted conductor daemon. That workflow is a single
`docker` step in `ghcr.io/cliangdev/conductor-render:1` running the **identical CLI command**,
`conductor creative render "$CREATIVE_ID" --renderer workflow --workflow-run-id "$CONDUCTOR_WORKFLOW_RUN_ID"`,
so it hits the same endpoints with the same contract; only the caller differs, and it authenticates with
a run-scoped token that is accepted for that Creative's renders only, stamped with the run's id
(`requestedBy` is null on such a render). The endpoint answers 422 `RENDER_NOT_NEEDED` for a Creative the
server can render by itself, 422 with the violations for an invalid one (nothing is dispatched), 409
`RENDER_RUN_ACTIVE` while a run for that Creative is queued or running, and 409 `RENDER_WORKFLOW_DISABLED`
or `RENDER_WORKFLOW_NAME_TAKEN` for a workflow someone disabled or already authored under that name.
`CreativeResponse.renderRun` (single `GET` only) carries the latest such run's status. Claude Code and the
CLI still render locally with no Workflow. When the workspace has a Cloud Run runtime target set up for rendering, the run executes there as a Cloud Run Job; otherwise it runs on your self-hosted daemon (`CreativeRenderRun.runtime` says which). Setup, the image and reuse in custom workflows:
[`workflows.md`](workflows.md#creatives-rendering-from-the-web-app-on-a-self-hosted-daemon).

`CreativeResponse.latestRender` (the last `SUCCEEDED`, non-preview render, with frames) appears on a
single-Creative `GET`; list reads carry only `latestRenderId` and `latestRenderThumbnailUrl` (the `4x5`
frame, or the first one), so a grid of creatives stays cheap.

## Draft, approve, commit — nothing is saved before a person has looked

Creating a Creative used to write a row, and upload its photos, before anyone saw an image. Every
Claude-driven flow is now **draft → look → approve → commit**, and the backend writes nothing until the
last step. Two MCP tools (`conductor-tools/src/mcp/tools/creative-drafts.ts`) carry it:

- **`preview_creative_draft`** takes the `create_creative` fields plus local files (`photoPath`,
  `clipPath`, `audioPath`, a story/carousel beat's own `photoPath`), an optional `baseCreativeId` (preview
  edits on top of a saved Creative) and `full` (every placement at full size instead of one contact
  sheet). It reads each local file's metadata the way `upload_creative_media` does, then calls
  `POST .../creatives/draft-spec` — the create body plus `previewOnly`, `baseCreativeId` and
  `localMedia` (`{ key: { kind, width, height, durationSeconds, hasAudio } }`). A local file travels as the
  media id `local:<key>` (`photo`, `clip`, `audio`, `beat-<n>`; keys match `^[a-z0-9][a-z0-9-]{0,63}$`).
  The endpoint runs the same validators as a create, persists nothing, and returns `{ spec, readiness }`
  (or a 422 naming the field, which the tool returns as a structured error without rendering). The spec
  is the shape `getSpec()` returns, with `local:<key>` left in place of every local media URL.
  `typeOverrides` (`{"<placementKey>": [fontSize, lineHeight?, letterSpacing?]}`) is accepted on create,
  update and draft-spec alike, validated the same way (real placement keys, sane numbers; else a 422).
  A contact-sheet preview also returns `checks` and `passed`: the sheet never runs the render's
  assertions, so `run({ checkPlacements: true })` renders each placement's `frame.html` in the same browser
  (a MOTION creative at its end card), keeps only the findings and drops the frame bytes. They are written
  to `manifest.checks` / `manifest.passed`; an `error` severity is exactly what the full render would fail
  on (e.g. `safeZone`), so the agent fixes the design and re-previews before asking for approval.
  Besides spill, safe zone, fonts, images, contrast and size, the assertions fail a frame that lost something
  the spec requires (`missingPhoto`: the spec has a photo URL but no visible, loaded photo layer;
  `missingLockup`: the Brand Kit has a logo but the frame has no lockup; `missingHeadline`), and warn
  (`bodyDropped`, with `placementKey`) when a body was supplied but the layout has no room for it at that
  placement — `stacked` drops it at `4x5`, every card of a 4:5 carousel included. The warning is not blocking:
  put the load-bearing words in the headline, or pick another layout.
- **`commit_creative_draft`** runs only after the person approved the image: it uploads each local file
  (the `upload_creative_media` code path), swaps `local:<key>` for the returned ids, calls
  `create_creative` (or `update_creative` at the current `version` when the draft has a
  `baseCreativeId`), then runs the full local render. It refuses a draft that is already committed. A CAROUSEL
  draft also carries its slideshow spec to the Creative; see [Organic slideshows](#organic-slideshows).

The local render uses `conductor-creative/job/file-transport.mjs`, a fourth transport beside the API one:
`run()` is unchanged. `getSpec()` rewrites each `local:<key>` to a loopback URL served by a small
Range-capable server (`job/server.mjs`) rooted at a temp dir holding links to the files — so the headless
browser and ffmpeg can load them — and `putFrame`/`putPoster`/`complete` write `sheet.jpg`,
`<placement>[-<index>].jpg|.mp4`, `poster-<placement>.jpg` and `manifest.json` into the draft folder.

Drafts live in `<project>/.conductor/drafts/<YYYYMMDD-HHMMSS>-<slug>/` (with a `.gitignore` that ignores
them) beside a `draft.json`: the request fields, the local-file map, `baseCreativeId`, a sha256 of the
spec, and `committed`. Both tools prune that folder (and only that folder) of drafts that are committed
or older than 14 days. A CLIP Creative has nothing to render, so it has no draft preview; a headline
variant is previewed with `baseCreativeId` but saved with `create_creative` and `variantOf`, never
committed.

## Generated photos

When the media library has no fitting photo, `generate_creative_image({prompt, aspect?, count?,
quality?, size?, referenceImagePaths?, useCodex?})` makes one on the person's machine. `aspect` is `9:16`
(default, 1440x2560), `4:5` (1664x2080), `1:1` (1920x1920), `2:3`, `3:2` or `16:9` (`portrait`, `square` and
`landscape` stay as aliases for `2:3`, `1:1` and `3:2`); `size` sets an exact `WIDTHxHEIGHT`; `count` is 1-3.
The result carries `placementFit`: for each placement (9x16, 4x5, 1x1) the full-bleed scale, the scale with
the video zoom (up to 1.12) and `ok` when it is at most 1.0. The result's `generator`
names the route (`openai-api` or `codex`) and model; `model` and `quality` report what was used.

- **Tool default route: OpenAI Images API.** Used when the call has no `useCodex`. Needs a key: `conductor config set-openai-api-key <key>` (kept
  in `~/.conductor/config.json`, so it works for Claude Desktop, which doesn't inherit shell env), or
  env `CONDUCTOR_OPENAI_API_KEY` / `OPENAI_API_KEY` (that precedence, then config). Model
  `gpt-image-2.5-sunburst` (override with `CONDUCTOR_IMAGE_MODEL`). Billed to that key per image, more at larger sizes; 20-45 s. Reference images go to
  the edits endpoint.
- **Codex: the skills' default.** `useCodex: true` runs the local `codex exec` (built-in image_gen,
  `gpt-image-2`) on the person's ChatGPT Plus (or higher) weekly allowance, shared with coding; it is
  honoured even when a key exists, and it honours the custom size (1440x2560 verified 2026-10-22). The
  `conductor-marketing-create` skill passes it on every call without asking, as
  it is cheaper than the API, and fall back to a call without it when Codex fails. Called without
  `useCodex` and with no key, the tool generates nothing and returns an error with
  `code: 'CODEX_CONFIRMATION_REQUIRED'`, `options` (set an API key / use Codex / skip) and a
  `nextStep`, so other callers still ask before spending the allowance.
- **Quality.** One setting for both routes: the `quality` input (`low|medium|high`), else env
  `CONDUCTOR_IMAGE_QUALITY` (ignored if not one of those), else `high`. Use `medium`/`low` only for
  throwaway drafts.
- **Codex cost.** The Codex model only orchestrates the image_gen call, so it runs on the cheapest
  settings, `codex exec -m gpt-6-luna -c model_reasoning_effort="low"`; the image itself is asked for at
  the resolved quality. Env overrides: `CONDUCTOR_CODEX_IMAGE_MODEL`, `CONDUCTOR_CODEX_IMAGE_REASONING`.
  If Codex rejects the model for the account, the run retries once on the person's default model
  (reported as `model: 'default'`).
- **No silent fallback.** An API error (bad key, quota) is reported, not retried on Codex.
- **Doctor.** `conductor doctor` has an "Image generation" check covering both routes: Codex (the
  skills' default) with its install and login state, then the OpenAI API fallback with model, quality
  and key source (verified against the model endpoint). Either route working passes.

- **Prompt.** The caller writes the scene; the tool adds fixed rules: photographic unless asked
  otherwise, no text, letters, logos, watermarks, UI or phone screens, and clean negative space for
  the headline. Pass `referenceImagePaths` when a real product must appear; never invent one.
- **Realism.** For photographic scenes the prompt also asks for an unretouched look (natural light,
  true colour, sensor grain), bans the AI look (waxy or glossy surfaces, over-sharpening, duplicated or
  floating objects) and asks for small real-world imperfections. The creative skills then run a realism
  check (`references/realism-check.md`) on every generated photo, regenerate up to twice on a fail, and
  report a verdict at approval. It is a quality bar, not concealment: the AI label still applies.
- **Output.** `<projectRoot>/.conductor/generated/<timestamp>-<slug>/image-N.png` (git-ignored,
  pruned after 14 days). The result returns the first image inline plus `files: [{path, width,
  height}]`, `provenance` and `revisedPrompt`. Look at every image before using it.
- **Provenance.** Each file gets a sidecar `<file>.provenance.json` (source "Generated with OpenAI <model> (API) on <date>" or "Generated with Codex
  image_gen on <date>", licence "Generated, house use", `aiGenerated: true`, plus the `model` and `quality` used).
  `preview_creative_draft` records it and `commit_creative_draft` / `upload_creative_media` use it
  automatically for that file, so a draft mixing a generated photo with the person's own music keeps
  each file's own provenance. The preview reports which local files still need provenance.
  `aiGenerated: true` means the AI label applies when the Post is published.
- **Use.** Pass a file's `path` as `photoPath` (or `sequence[].photoPath`) to
  `preview_creative_draft`. Never use a photo above 1.0x full-bleed at a placement (a 1024x1536 image at
  9:16 is 1.25x): check `placementFit`, and regenerate at the largest size in that aspect, use another
  photo or pick a framed layout.

## Attach — "Use in Post"

`POST .../creatives/{creativeId}/attach` links a `SUCCEEDED` render's frames to a Post as its own Assets (one per frame, same stored object, nothing copied) and
sets each inheriting destination's media by placement:

| Placement | Goes to |
|---|---|
| `9x16` | TikTok (its only format), and an Instagram target in `REEL` format |
| `4x5` | an Instagram target in `FEED` format |
| `1x1` | a Facebook target in `FEED` format |
| `story` | any target in `STORY` format — a story sequence's beats attach in order, and the target publishes them as that many stories (see [`publishing.md`](publishing.md#story-sequences)) |

This mapping is one small, independently tested class, `CreativePlacementTargetMapper` — pure and
stateless, so a new placement or platform is a switch arm, not a service rewrite. A target the mapping has
no opinion about (a Facebook or Instagram Reel, since reels are video and a Creative's frames are always
still images) is left alone. A `CAROUSEL` is mapped separately, with an explicit ordered selection per
destination — see [Finished images](#finished-images-carousel).

Only targets with `custom_media = false` are touched; a target that already chose its own media is
reported in `targetsSkipped` and never overwritten. Attach reconstructs the Post's *whole* target
selection and pushes it through `PublishTargetService#replaceSelection` — the same set-replace a person's
own edit in the UI goes through — so the approval gate reverts and the bundle hash changes exactly as if a
human had picked that media by hand. Sequence frames (a carousel's beats) attach in the order they
rendered, keyed by `sequenceIndex`; they are never re-sorted, because order is content.

**Alt text reaches Instagram.** Instagram is the only platform here with an `altText` option (sent as
`alt_text`, single feed images only). When attach gives an Instagram target exactly one still image, and the
target's options hold no non-blank `altText`, attach sets that option to the Creative's `altText`, or
`slideAltText[0]` when that is blank (`CreativePlacementMedia.Placements#altTextFor`). An alt text the author
already wrote is never overwritten; video, carousels and other platforms are skipped. The attach response's
`targetsUpdated[].altText` reports the text it set (null otherwise).

A destination added to the Post *after* an attach gets the same treatment (including the alt text rule above): when `replaceSelection` creates a new
target with no media of its own and the Post holds Creative frames, the target's media is the matching frames
(same `CreativePlacementMedia` rules). With no matching placement it inherits as before, except after a `CAROUSEL`: there, as at attach time, a
destination the carousel cannot publish to (a story, a reel, YouTube) gets an explicit empty selection so it
surfaces the "no media" blocker instead of publishing every slide; an explicit selection
is never overridden and existing targets are never touched.

## Media library page {#media-library-page}

Marketing → Media (`/marketing/media`) is the media library as a page of its own, not only the picker
inside the Creative editor. It is the only file library in the workspace: a Post's media is a link to a
Media row (the same stored bytes, nothing copied), whether it was uploaded to the Post, added with
"From media" / `upload_asset({mediaIds})`, or attached from a Creative with "Use in Post" (which links
the render frames). The former Asset Library page is gone; `/marketing/assets` redirects here. Each tile
and the detail panel show where the file is used (Posts and Creatives), with "On posts" and "Unused"
filters. It lists images, video and audio as tiles in natural label order ("slide 2"
before "slide 10"), with All / Images / Video / Audio tabs, a label search (a case-insensitive substring filter over the
whole library, so "slide 03" finds "01-import 4x5 slide 03"; the server's `labelPrefix` can't serve
it), and Unchecked / Blocked / AI-generated filters. Upload takes several files at once through the
same mint → signed PUT → confirm flow as the picker (`mediaUpload.ts`). Opening a tile shows a detail
panel: preview, facts, label / source / licence (200 characters), the AI-generated and Checked
switches, Block with a reason / Unblock, and Delete. The server's errors (409 when a Creative uses the
media or a Post does, naming the posts so they can be cleaned up first, or a READY Creative for a block; 400 for an over-long field) are shown as returned.

Select tiles with the hover/focus checkbox (shift-click for a range, keyboard accessible). The bulk bar
acts only on selected tiles the current search, tab and filters show; a selection they hide is kept
and counted as "hidden by filters". It
offers **Mark checked**, **Select set** (everything sharing the label before " slide", e.g. both the
`4x5` and `9x16` slides of `01-import`) and **Make carousel**. Make carousel takes images only. It
groups them by nearest placement (4x5 / 9x16 / 1x1, each within 2%), orders each group by natural label,
refuses more than 10 slides per placement (35 for 9x16), an image off every placement, or a blocked or
still-uploading image, and previews
the grouping with an editable name (prefilled from the common label prefix). Confirming creates a
`CAROUSEL` Creative and opens it; the server's 422 violations, if any, appear in the dialog.

Blocking media a Post already uses is allowed, but publishing is then held: the Post's gate raises
`MEDIA_BLOCKED` until the file is removed from it. This holds for an already-scheduled Post too: at publish time the target
fails with "<label> is blocked in Media — remove it from this post, then retry." and the platform is never called.

### Where a Creative is used

The Creative list carries `postCount` (distinct Posts that use a rendered frame of the Creative). The tile
shows "Used in N posts" and links to the Posts list filtered by `?creativeId=`; the editor lists the same
Posts under "Posts using this". `GET /work-items` accepts `creativeId` and `mediaId` to filter to Posts that
use a Creative or a Media item (a Media item's post count on its tile links to `?mediaId=`). The count is per
family and only returned when asked (`GET .../creatives?includePostCount=true`, which the web library
sends); API/MCP `list_creatives` is unchanged. The Posts list extras (`thumbnailUrl`, `publishTargets`,
`creative`) are likewise opt-in (`GET /work-items?enrich=true`) so `list_work_items` / `list_posts` keep
their payload and carry no signed URLs. Links from a Creative or Media tile open the Posts list with
`view=all`, so Done and published Posts are included.

`DELETE .../creatives/{id}/family` deletes a Creative's whole family (root and every variant) in one
transaction: all of it, or none. It is refused (nothing deleted) when any member is on a Post, naming each
Post by id and title, or when a hook experiment of the family is running. The Creatives page's bulk
delete uses it, once per selected family. The Posts, Creatives and Media pages share one sub-nav.

## Video (COND-24 PR1)

The media library (`creative_photo` — the table/entity name stays, the API calls it "media") now takes
video and audio alongside photos: `mediaKind` is `IMAGE` (the original photo library), `VIDEO` or `AUDIO`.
A `VIDEO` also carries `durationSeconds` (required), an optional `hasAudio`, an optional codec hint, and an
optional JPEG poster — minted through its own `POST .../photos/{photoId}/poster` (signed PUT) →
`POST .../poster/confirm` pair, exactly like the photo's own mint → PUT → confirm shape. `width`/`height`
are required for `IMAGE`/`VIDEO` and unused for `AUDIO`. Size ceilings are per kind (100 MB image, 1 GB
video, 50 MB audio); a video longer than 180 seconds warns ("longer than most platforms take") but never
refuses — there is no length cap here, platform caps are enforced at publish time by
`MediaTargetValidator`.

A Creative's `kind` — `STILL` (default, everything above), `MOTION`, `CLIP` or `CAROUSEL` (below) — picks what it is. `STILL`
is unchanged. `MOTION` (a branded animated video) is accepted by the write API today, but a render for one
currently refuses with 422 ("Motion creatives render in the next release") — PR2 fills this in. `CLIP` is a
finished video used as-is, with no brand layout at all: `clipMedia` is a map of `{"default": mediaId,
"<placementKey>": mediaId, ...}`, where `default` covers every placement the map doesn't otherwise name.
Every referenced id must be a `VIDEO` in the media library that has finished uploading, is not blocked, and
belongs to the same project — checked on every write, the same way a STILL Creative's `photoId` is. A CLIP
needs no layout, theme, headline or photo; going `READY` needs a caption and at least one clip instead of a
headline and a photo.

**A CLIP that keeps its own sound is assembled by this backend, not by a local job** (a CLIP with new audio renders locally: see [Music on carousels and clips](#music-on-carousels-and-clips)). `POST .../creatives/{id}/renders` for a
CLIP creative settles `SUCCEEDED` in the same call: one frame per placement the clip set covers. An
explicit `clipMedia` key (other than `default`) is that frame's own placement; `default`'s media resolves
to whichever registry placement's aspect ratio is closest to its own — among every registry placement, a
story's aspect counts as `9x16` (so a 9:16 default lands on `9x16`, not `story`) — and only fills that
placement in when nothing more specific already claims it. Each frame is a server-side `StorageService.copy`
of the source media (and its poster, if it has one) into the render's own path; `spec` is null for such a
CLIP response, since there is no local job to hand a spec to (a CLIP with new audio gets one). `previewOnly` is refused with 422 — there is
no contact sheet to assemble from a single finished file. A frame carries `durationSeconds`/`hasAudio`/
`posterUrl`/`contentType` alongside the usual width/height/size — null for a STILL frame, set for a CLIP
one.

**Attach is video-aware.** `CreativePlacementTargetMapper#matchesVideo` extends the STILL mapping: a
`9x16` video also fills TikTok (its one format), an Instagram or Facebook reel, YouTube when the set has no
`16x9` cut (it goes out as a Short), Facebook's feed when there is no `1x1` cut (Facebook publishes a lone Page
video as a Reel), Instagram's feed when there is no `4x5` cut (Instagram publishes a single video as a Reel), and any story-format target left unclaimed by a dedicated `story` frame; a `16x9` video
fills YouTube, and Facebook's feed only when the set has neither a `1x1` nor a `9x16` cut. `CreativeAttachService`
copies the video bytes into the Post's own asset prefix and records `durationSeconds` on the resulting
Asset (via `AssetService.StoredObjectInput`), so `MediaTargetValidator`'s reel/story/TikTok duration rules
run against it exactly as they would a directly-uploaded video.

The `16x9` placement (1920×1080, YouTube and landscape video) was added to the registry for this — it is
not in any Brand Kit's default enabled set, since it means nothing to a STILL Creative's brand layout.

## Finished images (CAROUSEL) {#finished-images-carousel}

`kind: CAROUSEL` posts finished images as-is: slides designed elsewhere (Figma, your own renderer,
device-framed screenshots) with no brand layout applied. A one-slide CAROUSEL is a finished still.

- `slides`: `{"default": [mediaId...], "4x5": [...], "9x16": [...], "1x1": [...]}`, ids in slide order.
  Allowed keys are `default`, `4x5`, `9x16` and `1x1`; a targeted placement without its own list uses
  `default`. If `placements` is omitted the server derives it from the non-`default` keys (only
  `default`: the placement nearest the first image's aspect).
- `slideAltText`: strings index-aligned with the slides; entry N describes slide N in every placement.
- Every id must be an `IMAGE` that finished uploading and is not blocked. `clipMedia`, `sequence`,
  `motion` and `photoId` are refused on a CAROUSEL, and `slides` on any other kind.

Validation (422 `violations[]`, each with `field`, `ruleId`, `message`): `placement`, `mediaNotFound`,
`mediaNotImage`, `mediaUploaded`, `mediaBlocked`, `slideCount` (1-10 for `4x5`/`1x1`/`default`, 1-35
for `9x16`), `slideAspect` (within 2% of 1080x1350, 1080x1920 or 1080x1080), `placementSlides` (a
targeted placement has no slides), `kindFields`, `slideAltText` (more entries than slides). Going
`READY` additionally needs `captionRequired` and `slidesRequired`.

Readiness items: `slides`, `caption`, `altText` (the Creative's alt text, or alt text on every slide),
`photoChecked` (every slide), all blocking; `mediaProvenance`; `aiDisclosure` (never blocking, shown
when any slide is AI-generated); `state`.

**Renders are assembled by the backend**, like CLIP (unless the carousel has a music track, see
[Music on carousels and clips](#music-on-carousels-and-clips)): `POST .../renders` settles `SUCCEEDED` in the
same call with one image frame per slide per placement, carrying `placementKey` and a 0-based
`sequenceIndex`. `previewOnly` is refused with 422. `latestRenderThumbnailUrl` is slide 1 of `4x5`
when present, else of the first placement. Because nothing renders locally (without a track), `render_creative` and
`commit_creative_draft` request the server render, and `attach_creative_to_post` does so itself when
a CAROUSEL has no render yet.

Attach picks one ordered list per destination (the full table, limits and the
`MULTI_IMAGE_UNSUPPORTED` blocker are in [`publishing.md`](publishing.md#carousels-from-a-creative)):
Instagram feed `4x5` else `1x1`; Facebook feed `1x1` else `4x5`; TikTok `9x16` else `4x5`. Reels,
Stories and YouTube cannot take the photos (with a music track they can take the slideshow video
instead).

Draft flow: `preview_creative_draft({kind: "CAROUSEL", slidePaths: {"4x5": [paths...]}})` reads each
local image's size and `.provenance.json` sidecar, asks `draft-spec` for violations and readiness,
and returns one contact sheet per placement (slides in order); nothing is saved. With a music track it
also renders the slideshow video and returns its path (`slideshowVideoPath`).
`commit_creative_draft` uploads the slides in order (labelled from the file stem), writes their ids
into `slides`, and requests the server render. To turn an existing media set into a Creative, upload
it in one call (`upload_creative_media` with `folder` or `files`, up to 100, three at a time, one
result per file) and find it again with `list_creative_media({labelPrefix})`. `label`, `source` and
`licence` are capped at 200 characters (a longer value is a 400).

## Organic slideshows {#organic-slideshows}

Organic posts (TikTok photo posts, Instagram carousels) are native-looking slideshows, not brand-layout
ads. The composer lives in `conductor-tools/src/lib/slideshow/`; the MCP tool is `compose_slideshow`.

- **Input:** a spec, `{id, slides: [...]}`, passed inline (`spec`) or as a JSON file (`specPath`). Slide
  types: `photo`, `screen` (a real app screenshot in a phone frame, optional `callouts`), `notes`,
  `messages`, `grid`, `stack` (with labelled before/after), `tier`, `week`, `receipt` and `placard`; up
  to 35 slides. Text is native box or stroke text placed by `y` (percent of the 9:16 frame). The field
  reference is `conductor-tools/src/lib/slideshow/spec.ts`.
- **Output:** PNG slides at 1080x1920 (`9x16`) and 1080x1350 (`4x5`), written under
  `.conductor/slideshows/`, plus layout `checks` (spill, safe zones, text size) and `passed`. Nothing is
  sent to Conductor.
- **Then a CAROUSEL.** The returned `slidePaths` go to `preview_creative_draft({kind: "CAROUSEL",
  slidePaths, caption, slideAltText})`, and after approval `commit_creative_draft`. The result is an
  ordinary [CAROUSEL Creative](#finished-images-carousel) that can also keep the spec (below).
- **The stored spec.** A CAROUSEL may carry `slideshowSpec` (`creative.slideshow_spec`, jsonb, nullable):
  the spec its slides were composed from, in the same shape as above except that every image field
  (`bg`, `shot`, `photos[].src`, `days[].img`) is `media:<id>`, the id of an IMAGE in the project's media
  library, and a `resolvedStyle` key is stripped (the resolved style stays in `slideStyle`). Create, PATCH and
  draft-spec accept it and `CreativeResponse` returns it. Validation answers 422 with a `ruleId`:
  `slideshowSpecKind` (not a CAROUSEL), `slideshowSpecInvalid` (structure: `id` 1-80 characters, 1-35
  slide objects, editable text at most 500 characters, finite numbers, a 256 KB cap; the CLI validates
  fully before sending), `slideshowSpecMedia` (an image field is not `media:<id>` of an image in this
  project; all refs are resolved in one query) and `slideshowSpecSlideCount` (every non-empty list in
  `slides` must have as many entries as the spec has slides). Like `slideStyle`, it is cleared by a PATCH
  that changes the slides (ids or order, any placement) without sending a spec, by changing the kind away
  from CAROUSEL, and by an explicit `null` (or `{}`); resending identical slides keeps it, and a draft over
  `baseCreativeId` inherits it on the same terms. A variant copies it.
- **Revisions.** Setting or replacing the spec writes a `creative_revision` row in the same transaction as the
  save: source `IMPORT` when the Creative had no spec, `COMMIT` when it replaced one or the spec was set at
  create. A revision snapshots the spec, slides, alt text and style, the Creative `version` after the save,
  the author, and a `text_diff` (`[{slide, path, old, new}]`, the editable strings that changed over the
  slides both specs have; empty for `IMPORT` and for a create). Resending an identical spec, and clearing,
  write none. `GET /api/v2/projects/{id}/marketing/creatives/{creativeId}/revisions` returns
  `{revisions: [...]}`, newest first, at most 50, to any project member (no spec or slides in the list; read
  the spec from the Creative). Which strings are editable is `conductor-creative/slideshow-text-fields.json`
  (patterns such as `texts[].t` and `rows[].items[]`, plus the 500-character limit), mirrored by
  `SlideshowTextFields` in the backend, which lists, applies and diffs them (parity test
  `SlideshowTextFieldsParityTest`).
- **Editing slide text (backend).** `POST /api/v2/projects/{id}/marketing/creatives/{creativeId}/slide-text-edits`
  (ADMIN or CREATOR) takes `{version, edits: [{slide, path, text}]}` (1-50 edits, `path` a concrete registry
  path such as `texts[0].t`) and answers 202 `{revision, renderRun}`. Nothing on the Creative changes yet: the
  edit is a PENDING `creative_revision` holding the candidate spec (`spec`), the text diff, the Creative
  `version` it was made against (`baseVersion`), `source` `EDITOR` (a signed-in session) or `MCP` (a user API
  key), and the id of the render run (`workflowRunId`). Checks, in order: 404 unknown Creative or non-member
  (403 for a REVIEWER); 422 `slideshowSpecMissing` (not a CAROUSEL, or no stored spec); 409 `version` is not the
  Creative's ("This Creative changed since you read it"); 409 `SLIDE_EDIT_ACTIVE` while a revision is PENDING
  (body `revisionId`), or 409 `RENDER_RUN_ACTIVE` while any render run of the Creative is queued or running
  (also `RENDER_WORKFLOW_DISABLED` / `RENDER_WORKFLOW_NAME_TAKEN`, as for a Render click); 422
  `slideTextEditInvalid` with `field: edits[i]` for each edit the registry refuses (not an editable path, a missing
  path, not text, over 500 characters, no such slide) or `edits` for a count outside 1-50; 422
  `slideTextEditNoop` when the candidate's diff is empty. The Creative row is locked for the request, and a
  partial unique index (`uq_creative_revision_one_pending`, V175) lets at most one revision per Creative be
  PENDING, so two edits cannot race past the check.
  - **Dispatch.** `CreativeRenderDispatchService.dispatchSlideTextEdit` fires the same `conductor-creative-render`
    workflow on the same runtime (the designated Cloud Run target when its render job is READY, else the
    daemon) with `type: "creative.slideshow-text"` and `revisionId` in the event. Both jobs set
    `REVISION_ID: ${{ event.revisionId }}`; a missing event field interpolates to the empty string, so the
    one-line script runs `conductor creative render-slides` when it is set and `conductor creative render`
    otherwise (see [the workflow](workflows.md#creatives-rendering-from-the-web-app-on-a-self-hosted-daemon);
    `CreativeRenderWorkflowYamlTest` runs the script under `sh`). A seeded copy of the workflow from before this is
    refreshed in place by the next dispatch (`CreativeWorkflowProvisioner` compares the YAML).
  - **The job's calls.** `GET .../revisions/{revisionId}` returns the revision's fields plus `spec`,
    `changedSlides` (distinct 1-based slides of the diff), `altTextFlags`, and the Creative's current `slides`
    and `slideStyle`. `POST .../revisions/{revisionId}/complete` takes `{slideMedia: {"9x16": {"3": "<mediaId>"},
    "4x5": {...}}, checks: [{slide, ratio, rule, severity, message}]}` and `.../fail` takes `{error, checks?}`.
    Access is the revision's own run token (the run's event must name this Creative and the revision's
    `workflowRunId` must be the token's run, else 403) or a person: any member may read, ADMIN or CREATOR may
    complete or fail, so an editor can run the job with their own key. A revision that is not PENDING is a 409.
  - **Apply (`complete`, one transaction, Creative row locked).** (1) A check with severity `error` FAILS the
    revision (checks stored, error "The re-rendered slides failed N checks: slide 3 9x16 safeZone (...)") and
    changes nothing else. (2) The Creative's `version` must still equal `baseVersion` and a spec must still be
    stored, else FAILED with "The Creative changed while the slides were rendering. Make the edit again." (3)
    `slideMedia` must be exactly the changed slides for each ratio the Creative has a non-empty list for
    (`9x16` and `4x5` keys; a `default` list counts as the closest ratio of its first image; `1x1`, or a
    `default` list of unknown size, is not re-rendered and is recorded as a warning check
    `{rule: "render", severity: "warning", message: "1x1 slides were not re-rendered"}`), and every media id must
    be an uploaded, unblocked IMAGE of this project; otherwise FAILED with the reason. (4) The new media is marked
    `checked` (the composer's own output). (5) The changed entries are replaced by index in every list that was
    re-rendered (the `default` list included), `slideshow_spec` becomes the revision's spec, and the version is
    bumped; `slide_style` is kept. (6) `alt_text_flags` is the list of slides whose alt text (lowercased,
    whitespace including newlines collapsed) contains an old text of that slide's diff (empty old texts are
    ignored). (7) The revision becomes APPLIED with snapshots of `slides`, `slideAltText` and `slideStyle`, the
    new `creativeVersion`, `appliedAt` and the checks. A FAILED outcome is still a 200 with the revision; only a
    malformed body (an unknown ratio, a slide that is not a positive number) is a 422 `slideMediaInvalid`, which
    leaves the revision PENDING.
  - **The render and the event.** Without a music track the apply renders instantly (`requestCarouselRender`
    semantics: the render is SUCCEEDED in the same transaction and linked to the revision as `renderId`). If that
    render is refused (for instance the caption was removed meanwhile) the apply stands and the revision's
    `error` says so. With a track the video is rebuilt by a second render run, queued after the apply commits
    (the revision's own run is still active and is ignored by the one-active-run guard; a refusal is noted in
    `error`, the slides stay applied); the job's render then follows the normal render endpoints, and when it
    SUCCEEDS `completeRender` links the oldest unlinked applied edit of that Creative version. Either way a
    `CreativeRevisionRendered(projectId, creativeId, revisionId, renderId)` Spring event is published once,
    **after the transaction that finished the render has committed**, and never when the render fails or the
    transaction rolls back. Listen with a plain `@EventListener` (it runs on the committing thread, no transaction
    bound); T2 has no listener, post propagation is the first.
  - **Stale revisions.** A PENDING revision whose run ended without completing it (FAILED, CANCELLED,
    `LOCAL_PICKUP_TIMEOUT`, no longer there, or finished without reporting) can never finish, so it reads as
    FAILED with the run's reason and is stored so in its own transaction. This happens on the revision's `GET`,
    the revision list, a single-Creative read and a new edit; a PENDING revision with no run is abandoned after
    35 minutes.
  - **What a reader sees.** `CreativeResponse` of a single-Creative read carries `pendingRevision`
    (`{id, status, createdAt, workflowRunId}` or null) and `latestRevision` (`{id, revisionNo, status, source,
    error, checks, createdAt}` of the newest revision, a FAILED one with its `error` and `checks`); the list read
    leaves both null to stay one query per kind. The readiness checklist gains a non-blocking `altTextStale`
    item for a CAROUSEL once an applied edit flagged a slide: not ok ("slide 2 alt text still quotes the old
    text") while that slide's alt text still contains the replaced text, ok once it does not.
- **Editing slide text in the browser.** A CAROUSEL with a stored spec shows **Edit text** under the slide on
  screen (ADMIN or CREATOR; disabled with a visible reason while an edit renders or the form has unsaved
  changes). It opens the slide's fields from the registry under plain names (Text 1, Callout, Note item 3,
  Bubble 2), one labelled textarea each; line breaks are kept. Beside them a live preview shows the slide at
  9:16 and 4:5 (the ratios the Creative has). The preview is the composer's own page: the slideshow modules
  of `conductor-tools/src/lib/slideshow/` (`spec`, `style`, `css`, `html` with the in-page measuring script,
  `text-fields`) have no Node imports, so `conductor-tools/scripts/sync-slideshow-core.mjs` (run by
  `npm run build`) compiles them to plain ESM and `.d.ts` in `conductor-creative/slideshow/` and copies the
  bundled fonts to `conductor-creative/slideshow/fonts/`. Both are committed, and
  `sync-slideshow-core.test.ts` fails when the committed copy differs from a fresh generation and checks the
  generated builder returns the same HTML as the TypeScript one. The web app imports
  `@cliangdev/creative-render/slideshow/*.js` and `scripts/copy-slideshow-fonts.mjs` (run by `predev` and
  `prebuild`) copies the fonts to `public/slideshow-fonts/` (gitignored), the preview's `assetBase`. The
  preview swaps each `media:<id>` for the media's display URL, resolves the style as the render job does
  (the spec's own `style`, then the Creative's `slideStyle`), and shows the page's checks by rule once it has
  measured itself (safe zone, spill, words per line, text size, font, image, contrast; errors first). A
  **Show safe zones** switch overlays the 220 / 430 / 140 px bands on 9:16. The checks are advisory; the render
  job's are the ones that count, and an error there refuses the save.
  **Save** is two steps. **Review and save** lists the changed fields, the Posts that use the Creative with
  what happens to each (`GET .../affected-posts`: back to review, new review round, updated, not changed), and
  where the render runs (`GET .../render-availability`: the runtime target, or the self-hosted daemon; with no
  target the page says to keep the daemon running or to run `conductor creative render-slides <revision id>`).
  **Save and re-render** POSTs the edits with the Creative's `version`, shows "Re-rendering…" and asks the
  Creative every few seconds until `pendingRevision` clears. Applied: the page reloads the slides. Failed: the
  error and the failing checks by slide, ratio and rule, with the edit still in the form. A 409 (stale version,
  an edit or render already running) and a 422 show the API's own message and leave the edit as it was. If an
  edit is already rendering when the page opens, the editor opens on that state and the page shows a note.
  **Text history** (Creative page) lists the revisions newest first: source (web editor, reviewer fix, agent,
  committed), author, time, status, and rows "Slide 3 · Text 1: old → new" with the changed words marked (a
  small word-level diff, no dependency). A long revision shows its first three changes. **Alt text**: after an
  applied edit, a slide whose alt text still quotes the replaced words (the revision's `altTextFlags`, while the
  readiness item `altTextStale` is not ok) shows "Quotes the old text — update" under its alt input, which
  clears as the text stops quoting them.
  On a Post, the edit's effect shows as **Changed since last review** (Content section: the Creative, who,
  when, the changed words) while the newest change opened the Post's current review round or is a reviewer's fix
  inside it, and as entries in the Activity tab (`GET .../work-items/{id}/content-changes`). A reviewer of an In
  Review Post sees **Fix text** beside "Open Creative" for a slideshow Creative with a spec; it opens the same
  editor with `postId`, whose confirmation says the fix stays in this round and names the other Posts that go
  back to review.
- **Spec through preview and commit (CLI).** `compose_slideshow` writes `spec.json` beside the slides (the
  input spec plus `resolvedStyle`). `preview_creative_draft` keeps it with a CAROUSEL draft: pass
  `slideshowSpecPath`, or leave it out and the `spec.json` in the folder of `slidePaths` is used when it is the
  one that composed them (its `id` prefixes the slide file names). It is validated with `validateSlideshowSpec`
  and its slide count must equal every `slides` list; a mismatch, or an auto-detected spec that cannot be read,
  warns in the preview's `note` and drops the spec, never blocking the slides (an explicit
  `slideshowSpecPath` that is unusable is an error). The draft records `slideshowSpecPath` and its own copy of
  the spec (`draft.json`: images as absolute paths, `resolvedStyle` stripped), so editing the file afterwards
  cannot change what is committed. The spec is not sent to `draft-spec` (that call knows uploaded media, not
  local files). `commit_creative_draft` uploads each distinct input image once (`bg`, `shot`,
  `photos[].src`, `days[].img`; deduped by absolute path, each file's `.provenance.json` sidecar winning over
  the call's `source`/`licence`/`aiGenerated`, and never marked `photoChecked`), rewrites the references to
  `media:<id>`, and sends `slideshowSpec` in the create or update body; uploaded spec images are remembered in
  `draft.json` (`specImages`) so a retry never re-uploads. A draft over `baseCreativeId` whose new slides come
  with no spec warns that committing will clear the base's spec. `get_creative` returns `slideshowSpec` and a
  compact `textFields` list (`[{slide, path, text}]`, slides from 1, from `listTextFields`); above 8000 JSON
  characters the spec is summarised and `textFields` carries the text.
- **Text-field registry (TS).** `conductor-tools/src/lib/slideshow/text-fields.ts` holds the patterns from
  `slideshow-text-fields.json` and `listTextFields(spec)`, `applyTextEdits(spec, edits)` (throws on a slide
  out of range, a path outside the registry, an absent path, a non-string target or text over the maximum;
  never adds or removes entries) and `diffText(a, b)` (the `text_diff` shape, over the slides both have). A
  parity test asserts the constants equal the JSON, as the backend's does.
- **Backfill: `attach_slideshow_spec` / `conductor creative attach-spec <creativeId> <specPath> [--force]`.**
  For a CAROUSEL committed before specs were kept. `specPath` is a source spec (relative image paths resolve
  against its folder) or a compose_slideshow output `spec.json` (absolute paths and `resolvedStyle`). It
  refuses a Creative that is not a CAROUSEL, one that already has a spec (without `force`), and a spec whose
  slide count differs from a stored list (not even `force` overrides that: the backend would answer
  `slideshowSpecSlideCount`). It then re-composes the spec into a temp folder at each ratio the Creative has a
  list for (`9x16`, `4x5`; `default` or `1x1` by the shape of its images, 1080x1920 or 1080x1350, anything else
  is skipped with a note), downloads the stored slides and scores each pair. The score is the **lowest tile
  SSIM** over a 6x8 grid (ffmpeg's `ssim` filter, already resolved for media probing; no image library): a
  changed headline is a small area, so a whole-frame score would average it away (0.98 on a busy photo) while
  the tiles under the text drop to 0.77-0.83. Measured with this composer: the same spec re-composed scores
  1.0; a PNG re-saved as a JPEG 0.99 (0.96 on a worst-case synthetic test card); a changed box colour 0.999
  (luminance-based, so not caught). Any slide below **0.95** refuses the attach and names the slides, unless
  `force`, since it means these slides are not what this spec makes (edited since, or composed before the
  bundled fonts below). Otherwise it uploads the spec's images as commit does, and PATCHes `slideshowSpec` (plus
  `slideStyle` from `resolvedStyle` when the Creative has none) at the Creative's current `version`. The result
  reports what was attached, the compared count, the lowest similarities and any skipped lists; verify with
  `get_creative`.
- **Editing slide text: `update_creative_slide_text` / `conductor creative render-slides`.** For a CAROUSEL
  with a stored spec. `update_creative_slide_text({creativeId, edits: [{slide, field, text}], version?})` takes
  `field` from a `get_creative` `textFields[].path` (slides from 1; it replaces an existing string and never adds
  or removes one), reads the Creative's `version` when it is not given, and POSTs
  `.../creatives/{id}/slide-text-edits` (body `{version, edits: [{slide, path, text}]}`). It returns at once
  (dispatch-status): `revisionId`, `status` PENDING, `changedSlides`, the `diff` (at most 20 entries, long text
  clipped to 120 characters), `render` (`runtime` `target` or `self-hosted`, the run's id and status) and a
  `nextStep`. Nothing on the Creative changes until the render passes; follow it with `get_creative`, which now
  carries `latestRevision` (`id`, `revisionNo`, `status`, `source`, `error`, `createdAt`, and for a failure
  `failingChecks` as `slide N 9x16 rule: message`, plus a `warningCount`) and, while one renders,
  `pendingRevision` with a note. A 409 for a stale `version` reads "This Creative changed since you read it";
  any other 409 means an edit or render of that Creative is still running. A 422 is shown as
  `[field] message` per violation (`slideshowSpecMissing`, `slideTextEditInvalid`, `slideTextEditNoop`).
  - **The job.** The render Workflow runs `conductor creative render-slides <revisionId> --creative <id>
    [--workflow-run-id <id>]` in the render image with the run's scoped key (`CONDUCTOR_API_KEY`,
    `CONDUCTOR_API_URL`, `CONDUCTOR_PROJECT_ID`), or on a person's machine with their own `conductor login`.
    It `GET`s `.../revisions/{revisionId}` (the edited spec, `changedSlides`, the Creative's current `slides`
    lists and `slideStyle`), re-renders the changed slides at every `9x16` / `4x5` list the Creative has (a
    `default` list counts as the ratio of its images; a `1x1` list is left as it is), downloads only the images
    those slides use, and composes **only the changed slides**: a spec holding just them, with the Creative's
    `slideStyle` as the inline style and the bundled fonts, so the pictures are those a full compose makes
    (`slideshow-subspec-equivalence.test.ts` pins that slide k alone is byte-identical to slide k of the whole
    spec; a `grain` slide's feTurbulence texture can differ by one level in a few pixels from run to run of the
    browser itself, also between two full composes). Checks come back numbered 1..n of the sub-spec and are mapped
    to the real slide numbers; the composer's "no provenance sidecar" warning is dropped (it is about committing
    local files). Any `error` check posts `.../revisions/{id}/fail` with `{error, checks}` and uploads nothing.
    Otherwise each PNG is uploaded as media labelled `<displayId> slide <n> <ratio> r<revisionNo>` with the
    source, licence and `aiGenerated` copied from the media it replaces, and `.../complete` gets
    `{slideMedia: {ratio: {slide: mediaId}}, checks}`; the backend then applies it or rejects it (for instance
    because the Creative changed meanwhile). A crash posts `/fail` with the message, then exits 1; a failed layout
    check also exits 1 (with the failing checks printed); a revision that is no longer PENDING is reported and
    left alone.
  - **No runtime target or daemon?** An edit with no Cloud Run runtime target and no `conductor start` daemon
    waits for a runner that never comes. Run the same job yourself from any folder, after `conductor login`:
    `conductor creative render-slides <revisionId> --creative <id>` (the `revisionId` is in
    `update_creative_slide_text`'s result and in `get_creative`'s `pendingRevision` / `latestRevision`). It needs
    a browser (Chrome, Edge or Playwright's Chromium) and signed-in access to the project's media; the result is
    identical to the Workflow's.
- **Fonts:** the bundled TikTok Sans (600, 700, 800) under the SIL Open Font License, in
  `conductor-tools/assets/fonts/` with `OFL.txt`. The slide types that imitate a system UI (`notes`,
  `messages`, `week`, a `grid` photo's `badge`, `receipt`, `placard`) used Apple system fonts (SF Pro,
  Helvetica Neue, Georgia, Courier New) that do not exist on the Linux render image, so they now use bundled
  OFL families in the same folder, each with its licence file: Inter 400/600/700 (`OFL-inter.txt`), Source
  Serif 4 400/700 (`OFL-source-serif-4.txt`) and Courier Prime 400/700 (`OFL-courier-prime.txt`). Only those
  families plus a generic keyword are named, so the output is the same on every machine. A slide declares only
  the faces its type uses (a photo or screen slide's HTML is unchanged apart from the measuring script and the
  unused font-family declarations, which `slideshow-html-stability.test.ts` pins against golden HTML), and the
  in-page `font` check treats a bundled face that will not load as an error (the package files ship through
  `assets/` in `package.json` `files`). Slides composed before this change look different on those types, which
  is why `attach_slideshow_spec` may refuse older notes, messages, week, receipt or placard slides.
- **Contrast (`contrast` check, a warning).** For each text element (`[data-fg][data-ts]`) the in-page rule
  compares the text colour with what is behind it, by the WCAG relative-luminance ratio (the formula of
  `conductor-creative/assertions.js`), and warns below 3:1 naming the slide and the ratio. Boxed text and the
  callout pill are judged against their box; outlined text (`stroke`, `sub`: a text stroke or shadow blur of
  2px or more) against the outline colour; text with neither against the mean luminance of the pixels behind
  it. A `file:` image taints a canvas, so for that case the composer shoots the slide again with its text
  hidden and the page measures that screenshot (it includes the dim layer, grade and any `bgColor`); any
  failure skips the check. It never fails a slideshow in this version.
- **Style.** The native look is the default. A `SlideshowStyle` (`fontFamily`, a name or comma list such as `Inter, sans-serif`; `fontUrl` for an https CSS
  stylesheet such as Google Fonts, `boxBg`, `boxInk`, `darkBg`, `darkInk`, `strokeInk` (the colour of outlined text and sub text; white when a stored style has none), `outline`, `accent` as
  `#RRGGBB`, `defaultText` of `box|dark|stroke`, `textSize` 24-120, `subSize` 16-96; all optional)
  restyles the text boxes, stroke text and callouts. It can live on a Brand Kit (`slideshowStyle`,
  nullable; set with `create_brand_kit` / `update_brand_kit`) or be passed one-off to
  `compose_slideshow`. Resolution is native, then the kit's style (`brandKitId`; refused with a clear
  error when the kit has none; unknown fields in a kit's style are ignored), then the spec's own top-level `style` (`null` = no layer), then the inline `style` on top. In the spec's and the inline `style` a field left out inherits from the layer below, while a field set to `null` resets it to the native value (`fontFamily: null` = bundled TikTok Sans and no `fontUrl`); in a kit, null and absent both mean "not set by the kit". `fontUrl` is checked only against the shared pattern (https only, no whitespace or quotes, at most 2048 characters) and `fontFamily` needs at least one family name; an invalid kit style is reported naming the kit. A new `fontFamily` keeps the inherited `fontUrl` only when its first family is the same (case-insensitive). The font check loads the list's first non-keyword family (generic and system keywords such as `sans-serif`, `-apple-system` and `BlinkMacSystemFont` are emitted unquoted and never checked; a list of only keywords has no custom-font check) even on slides with no styled text, and checks the bundled TikTok Sans whenever any text uses it (no custom font, or any placard slide). A custom font loads from `fontUrl`;
  a font that fails to load, or a `fontFamily` that is not installed when there is no `fontUrl`, is a blocking `font` check naming the family; notes, week, receipt,
  placard and messages slides keep their own bundled fonts (Inter, Source Serif 4, Courier Prime; see Fonts). The resolved (full) style is written to
  `spec.json` as `resolvedStyle` (the spec's own `style` is kept as given and a re-compose ignores `resolvedStyle`), returned by the tool as `style`, and recorded on the Creative as `slideStyle`
  (CAROUSEL only; native slides record the native defaults) so a look can be reused later. The rules
  (native values, keyword list, bounds, patterns) live once in `conductor-creative/slideshow-style.json`; the
  web Brand Kit form imports it and the CLI and backend tests assert their copies equal it. A kit style with no
  known non-null field counts as no style. A saved Creative keeps its recorded `slideStyle` unless a patch (or a
  draft over `baseCreativeId`) changes the slides (a different media id list or order for any ratio) without
  sending a `slideStyle`, which clears it; resending identical slides (the web editor does on every save) keeps
  it; an explicit `null` (or `{}`) clears it on any kind.
  `create_brand_kit` with `copyFrom` starts a new kit from another kit's voice, tokens and font
  (images are not copied).
- **Local rendering:** like Creatives, slides render on the user's machine with the system Chrome; no
  backend job.
- **Not the ad path.** Slides carry no logo, badge or offer; branded ads still use the brand layouts
  (STILL). The skills' guidance (post anatomy, archetypes, text and honesty rules) is
  `references/slideshow-formats.md` in the `conductor-marketing-create` skill.

## Motion (COND-24 PR2)

`MOTION` is a branded animated video: the same copy and look as a STILL Creative (layout, theme, headline
with an accent phrase, body, `photoId`, lockup, `layoutOverrides`, placements opt-ins), plus two JSON
columns nothing else touches, `creative.motion` and `creative.audio`.

`motion` is `{preset, durationSec, background: {source, motion, clipMediaId?, clipStartSec?}, endCard}` —
`preset` (`fade-up`/`word-by-word`/`accent-pop`/`none`) and `background.source`/`background.motion` are
plain strings, not OpenAPI enums, so an invalid value comes back from `CreativeValidator` as a 422 with a
field path like `motion.preset`, the same shape every other Creative violation uses, rather than a generic
400 from Jackson rejecting an unknown enum literal. A `photo` background reuses the Creative's own
`photoId`; a `clip` background points `clipMediaId` at a `VIDEO` in the media library (uploaded, unblocked,
same project, `clipStartSec` less than the clip's own duration). `audio` is
`{source: clip|track|none, trackId?, volume?, fadeOutSec?}` (CAROUSEL and CLIP take it too, below) — `track` needs an `AUDIO` media id; `clip`
needs a `clip` background whose media actually has an audio track (`hasAudio=true`).

**MOTION has no sequences.** A MOTION creative is one animated frame per placement; `sequenceKind` /
`sequence` (a story or carousel) belong to STILL creatives. `CreativeValidator` refuses the combination on
every write — create, patch and draft-spec alike — with a 422 (`ruleId` `motionNoSequence`, field
`sequenceKind`), before anything renders. (It used to be accepted and rendered only the first beat, with the
photo and logo missing and every check passing.)

**Defaults are applied on every write**, by `CreativeService#resolveMotion` — never left for the client to
supply or the renderer to guess: `preset` "fade-up", `durationSec` 8, `background.source` "photo",
`background.motion` "zoom-in" (photo backgrounds only), `endCard` true, and `audio.source` "clip" when the
resolved background is a clip whose media has audio, else "none" (`audio.volume` 0.8, `audio.fadeOutSec` 1).
A `PATCH` that omits `motion`/`audio` keeps the Creative's current (already-defaulted) value, exactly like
every other field patch semantics here — whole-field replace, not a deep merge.

**READY-state rules mirror STILL's, conditionally**: a headline is always required; a photo is required
only when `background.source` is `"photo"` (the default) — a `clip` background needs no `photoId` at all,
since the video itself is the background.

**Rendering is a local job, exactly like STILL** — `POST .../renders` no longer refuses MOTION with 422;
the render spec (`CreativeRenderSpecCreative`) gains `kind`, `motion` (with `background.clipUrl`, a signed
GET of the background clip, only when the background is a clip), `audio` (with `trackUrl`, a signed GET,
only when the source is a track) and `clipHasAudio` (whether the resolved background clip's media has an
audio track — null when the background is a photo). `previewOnly` is allowed for MOTION (a contact-sheet
preview of key animation moments), unlike CLIP, which has no local job to preview.

**The frame PUT accepts `video/mp4`** (`durationSeconds`/`hasAudio` query params, stored on the frame — at
most 500 MB, well above a STILL/`sheet` frame's 20 MB) alongside the existing JPEG/PNG. A second endpoint,
`PUT .../renders/{renderId}/frames/{placementKey}/poster`, uploads the frame's poster JPEG (the end-card
frame) once the frame itself is stored — same 20 MB ceiling as an image frame, only accepted while the
render is `RUNNING` (409 once it has settled), refused with a plain 400 if no frame is stored yet for that
placement/index to attach a poster to. A frame's `posterUrl` is populated either this way (MOTION) or by
copying the source media's own poster (CLIP) — the response shape does not distinguish which.

**Attach treats a MOTION frame exactly like a CLIP one** — `CreativeAttachService`'s video/image branch
keys off the frame's own `content_type` (`video/` prefix), not the Creative's `kind`, so
`CreativePlacementTargetMapper#matchesVideo` needed no changes at all: a MOTION render's `9x16` frame fills
TikTok and an Instagram/Facebook reel exactly as a CLIP's would.

**Readiness for MOTION** is STILL's photo-verdict checklist when the background is a photo, or the
background clip's own source/licence when it is a clip (`clipProvenance`); plus the audio track's own
source/licence when `audio.source` is `"track"` (`audioProvenance`, which now applies to a CAROUSEL or CLIP with a track too) — a licensed music track needs the same
provenance discipline as a photo or a clip. Alt text is advisory (non-blocking) for MOTION, like CLIP,
since both are video.

## Music on carousels and clips {#music-on-carousels-and-clips}

A library `AUDIO` track is no longer MOTION-only. `audio` is
`{source: clip|track|mix|none, trackId?, volume?, fadeOutSec?}` on three kinds:

| Kind | `source` | Notes |
|---|---|---|
| MOTION | `clip`, `track`, `none` | unchanged; volume 0.8, `fadeOutSec` 1 |
| CAROUSEL | `track`, `none` | a track makes a 9:16 slideshow video beside the slide images; volume 1.0, `fadeOutSec` 1 |
| CLIP | `clip` (default), `track` replaces its sound, `mix` plays the track under it, `none` mutes it | `mix` is refused (422 on `audio.source`) when the clip has no sound; track volume 1.0, mix volume 0.3, `fadeOutSec` 1 |

`trackId` is required for `track` and `mix` and must be an uploaded, unblocked `AUDIO` media in the
project. Going to review needs the track's `source` and `licence` recorded (`audioProvenance`, blocking) for
every kind. Deleting or blocking a track a Creative uses is refused with 409, the same as a photo; the
library's `creativeCount` for a track counts MOTION, CAROUSEL and CLIP Creatives using it, and `postCount`
the Posts that link a render frame made with it.

**Why a slideshow video.** No platform takes a chosen track with photos: Instagram's carousel container has
no audio parameter, TikTok photo posts take only `autoAddMusic` (TikTok picks the song), Facebook photo
posts have no audio, and YouTube has no photo post. So a carousel with a track gets a companion MP4 and each
publish target chooses `carouselAs: photos | video` (see [`publishing.md`](publishing.md#carousels-from-a-creative)).

**`slideshow`** (CAROUSEL only; refused on other kinds): `{slideDurations?: number[], zoom?: boolean}`.
`slideDurations` has one entry per `9x16` slide, each 1 to 10 s; the default is 3 s for the first slide
and 2.5 s for the rest (the TikTok-native pace, which keeps 20 slides under 60 s). `zoom` (default true) is a
slow push-in on each slide. Slides cross-fade for a fixed 0.5 s, so the video lasts the sum of the
durations minus 0.5 s per join. It is built from the `9x16` slide list (falling back to `default`).

**Where it renders: on the user's machine, never the backend.** A render needs the local job when:

- a CAROUSEL has `audio.source: track`;
- a CLIP has `audio.source` of `track`, `mix` or `none`.

Everything else keeps the instant server path (a CAROUSEL without a track, a CLIP with `clip` or no
`audio`). For the local cases `POST .../renders` returns a `RUNNING` render with a `spec`, like MOTION. For a
CAROUSEL the backend links the slide image frames at request time, as it always did; the local job then
adds one more frame, placement `9x16` with no `index`, `video/mp4`, plus its poster, and completes the
render. For a CLIP the job writes one video frame per placement (re-muxed with `-c:v copy` where it can) and
a poster. `render_creative`, `commit_creative_draft` and `attach_creative_to_post` route this by the
Creative's kind and audio (`needsLocalRender` in `conductor-tools/src/lib/creative-local-render.ts`), so a
caller needs nothing extra beyond ffmpeg on that machine. The audio chain is the same for both: fade in
0.5 s, fade out `fadeOutSec`, then a two-pass loudness normalisation to -14 LUFS with `volume` folded into
the target and a -2 dBTP ceiling, AAC 128k at 48 kHz stereo; a track shorter than the video loops. MOTION
keeps its current audio output unchanged (no normalisation).

`CreativeResponse.renderedVideo` is the video frame of the latest succeeded, non-preview render of a CAROUSEL
or CLIP (`renderId`, `frameId`, `placementKey`, `url`, `posterUrl`, `durationSeconds`, `hasAudio`), null
otherwise. A carousel's `latestRenderThumbnailUrl` still prefers its first slide image over the video's
poster. `get_creative` returns `audio`, `slideshow` and `renderedVideo`.

**Preview.** `preview_creative_draft` for a CAROUSEL with `audio.trackId` or `audioPath` renders the slideshow
MP4 on this machine and returns its path as `slideshowVideoPath` next to the contact sheet, so the person
can watch it before approving. A CLIP still has no draft preview (look at the MP4 after `render_creative`).
`audioPath` uploads on commit; pass the track's `source` and `licence` to `commit_creative_draft`.

**Attach.** A CAROUSEL with a track and a `9x16` video frame resolves `carouselAs` per target at attach (an
explicit value wins; otherwise `video` for TikTok, YouTube and any reel-format target, `photos` for Instagram
and Facebook feed) and writes it into the target's options. `photos` sends the slide images only, never the
video; `video` sends the one `9x16` MP4 on every platform (a single video on an Instagram or Facebook feed
target already publishes as a Reel). A CAROUSEL's photo selection also excludes video frames now, which
fixes TikTok photo posts receiving the MP4 beside the slides. With no track, the key is never written. A CLIP
or MOTION with a new track is an ordinary video, so the video-aware mapping above applies unchanged.

## What this backend does not do

No image processing beyond the screenshot the local job takes: Playwright's own JPEG/PNG encoder does the
only "re-encoding" that happens, and there is no server-side cropping and no Cloud Run render job. Every
render is a well-known local (or self-hosted-Workflow) job PUTing frames it already produced; this
backend's job is bookkeeping, storage and the attach mapping above.

## Performance and experiments (COND-24 T5)

**Attribution** (`CreativeAttributionResolver`) answers "which published destinations actually sent this
Creative": `creative_render_frame` → `assets.creative_frame_id` → the owning Post's publish targets,
resolved through the same `PublishTargetMediaResolver` the approval gate uses — an inheriting target
counts only while the frame's asset is still part of the Post's shared media, and a target with its own
explicit selection counts only while that frame's asset is among it. A frame later replaced on its Post
stops counting from that point on; nothing is backfilled.

`CreativePerformanceService` rolls this up per variant: posts, views, engagement rate
`(likes+comments+shares+saves)/views`, average view percentage where a platform reports it (YouTube), and
`views72h` — the *snapshot nearest at or after `fireTime + 72h`*, deliberately a different read of the
metric history than `views`/`engagementRate`/`avgViewPct`, which come from each destination's absolute
latest snapshot. `GET .../marketing/creatives/{creativeId}/performance` returns the whole family (every
lettered sibling); `GET .../marketing/insights` carries the top ten variants by views inside its window as
`creatives`, the same way it carries top/bottom posts.

**`creative_experiment`** (V143) is a hook experiment: two or more variants of one family, each published
as its own Post, decided by `CreativeExperimentService`. `create` needs the family to have at least two
variants and refuses a second `RUNNING` experiment on the same family with 409 (`uq_creative_experiment_
running_per_family`). `decide` — called on demand via `POST .../experiments/{id}/decide`, or by the
`conductor-marketing` weekly feed pull for every `RUNNING` experiment in the project — settles once every
variant has at least one published, attributed destination with a snapshot at or after `fireTime +
windowHours` (default 72): the comparison metric is `avg_view_pct` when every variant reports it, else the
metric requested at creation (default `views`); an exact tie among the leaders settles `INCONCLUSIVE`
immediately (the window has, by definition, already fully elapsed for everyone at that point). While any
variant is still short of its own deadline, `decide` is a safe no-op that leaves the experiment `RUNNING`
— unless the last outstanding variant's deadline (its own `fireTime + windowHours`, or the experiment's
`createdAt + windowHours` for a variant never published at all) is more than seven days past, in which case
the experiment gives up as `INCONCLUSIVE` rather than wait forever. `summary` records the per-variant
numbers used (or why a decision couldn't be made) and is never recomputed after `decidedAt` is set.

**Confirming a winner** is the one human action in this loop: `POST .../experiments/{id}/confirm-winner`
appends the winning variant's headline to its family's Brand Kit `approved_lines` (deduplicated) and
stamps `winnerLineConfirmedAt`/`By`. It requires ADMIN or CREATOR, requires a `DECIDED` experiment with a
winner, and is idempotent — `decide` never calls it, and calling it twice never appends the line twice. No
experiment ever changes brand copy on its own.

The weekly `conductor-marketing` digest (`docs/knowledge.md`'s "Metrics digests") gets a **Hook winners**
section: `MarketingInsightsConnector.fetchData` decides every `RUNNING` experiment for the project, then
reads every experiment `DECIDED` since the start of that pull's trailing window and adds it to the raw
payload as `hookWinners` (creative, headline, metric, per-variant numbers). `DigestPayloadBuilder` copies
that key onto the narrator's payload verbatim when non-empty — the one narrow exception to "the narrator
never sees raw numbers", since a hook winner is already a finished decision, not a raw series — and
`MetricsDigestService` treats a non-empty `hookWinners` as material on its own, so a decided experiment is
narrated even on an otherwise-flat week.
