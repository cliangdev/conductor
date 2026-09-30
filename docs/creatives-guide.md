# Creatives

A Creative is a photo, a headline, and a layout, rendered from your workspace's Brand Kit into
upload-ready artwork for every placement you publish to. You build one once and it comes out the
right size and shape for TikTok, Instagram, Facebook, and anywhere else you've enabled. A rendered
Creative becomes a Post's media — the approval gate, scheduling, and publishing itself all still
happen on the Post, exactly as before. This guide is for making the artwork; see
[docs/publishing.md](publishing.md) for what happens after you attach it.

For how any of this works under the hood, see [docs/creatives.md](creatives.md).

## Quick start

- Install or update the CLI: `npm install -g @cliangdev/conductor@latest`, then `conductor login`
  and `conductor init` in your project — already set up? Just update; skills refresh automatically
  from 0.25.0.
- Set up your brand first — **Settings → Brand** in the web app, or ask Claude something like "Set
  up our brand kit in Conductor from our website" (Claude will ask for the URL).
- In Claude Code, run `/conductor:creative` and describe what you want — e.g. "Make an ad for our
  spring sale, headline leads with the discount," "Make a 9:16 TikTok video from our latest photo
  with the headline *half price*," or "Upload this finished video as a Clip and put it on a new
  Post." In Claude Desktop (or Claude Code), skip the slash command and just ask the same way —
  Claude uses the same Conductor MCP tools either way.
- No Claude Code/Desktop? **Marketing → Creatives → New creative** in the web app does the same job.

The rest of this guide is the full walkthrough, starting with your Brand Kit.

## 1. Set up your brand

Go to **Settings → Brand**. This is where your workspace's visual identity and copy rules live —
every Creative renders against one of these kits, and nothing brand-specific is ever hardcoded
into the product.

- **Name** — what this kit is called (you can have several; see below).
- **Font family** and **Font URL** — a Google Fonts (or similar) stylesheet URL. The accent phrase
  in a headline always renders in italic, so your font URL must include the italic face. On Google
  Fonts that's the `ital` axis, e.g. `family=Poppins:ital,wght@0,400;0,800;1,800`. Leave it out and
  the browser fakes the slant — the space after the accent phrase visually closes up.
- **Logos** — Mark, Wordmark · dark (for light frames), Wordmark · light (for dark frames), and
  Store badge. Each wordmark slot name is the logo's own color, not the frame it's used on: the
  renderer puts the light-colored wordmark on dark frames and the dark-colored one on light frames
  and chips, so it reads against the background. All four are optional; a kit with no logo uploaded
  simply renders without a lockup for that slot, no placeholder.
- **Tokens** — the colour palette a Creative renders with: `accent`/`accent2` (the accent-phrase
  and CTA colours), `darkBg`/`darkInk` (dark-theme background and text), `lightBg`/`lightCard`
  (light-theme background and card surfaces), and `ink`/`ink2` (primary and secondary text). Set
  each as a hex value.
- **CTA claim** — the short call-to-action line shown near the logo lockup (e.g. "Download free").
- **"Headline must carry exactly one accent phrase"** — a switch. When it's on, every headline you
  write must wrap exactly one phrase in `*asterisks*`, e.g. `Spring colours, *half price*.` —
  never zero, never two. That phrase is what renders in italic accent colour.
- **Rules** — your copy rules: a regex pattern, optional flags, a message to show when it fails,
  which fields it checks (headline/body/caption), and an optional exception pattern for cases the
  rule shouldn't catch. Use **Test a line** to type a headline and see immediately whether it
  passes.
- **Approved lines** — a running list of headlines that have already been cleared. Confirming a
  hook experiment's winner (see below) adds to this list automatically; you can also add or remove
  lines by hand.
- **Placements** — which frame shapes this kit renders by default. Three are on by default: `9:16`
  (TikTok and Reels), `4:5` (Instagram feed), and `1:1` (Facebook feed). Three more are available
  but off by default: `story` (Instagram/Facebook Stories), a `1.91:1` link ad, and `2:3`
  (Pinterest). A Creative can also opt into an extra placement beyond the kit's defaults on its own.
- **Knowledge page** — the path to a wiki page holding this brand's positioning, voice, and
  approved verbatim lines in prose. An agent making a Creative for you reads this before writing
  copy.

A project always has at least one kit ("default", marked with a star). Use **New kit** to add
another, **Set as default** to change which one new Creatives use, and **Delete kit** once you have
more than one (you can't delete the only kit, or one a Creative still uses).

## 2. Make a creative

You can build a Creative from Claude Code/Desktop or from the web app — pick whichever fits how you
work. Either way, rendering the final artwork always happens locally, not on Conductor's servers.

### a. In Claude Code or Claude Desktop (recommended)

**Prerequisites:**

- `npm install -g @cliangdev/conductor` (0.25.0 or later — earlier versions work for creatives but
  don't have `/conductor:creative`), then `conductor login` and `conductor init` in your project —
  see the [conductor-tools README](../conductor-tools/README.md) if you haven't set these up
  before. Already set up? Just update the package; skills refresh automatically from 0.25.0.
- Google Chrome or Microsoft Edge installed, or run `npx playwright install chromium` once.
  Rendering launches whichever it finds first.

In Claude Code, run `/conductor:creative` and describe what you want. In Claude Desktop (or Claude
Code, if you'd rather skip the slash command), just ask Claude directly — the same MCP tools drive
either flow, e.g.:

> Make an ad for our spring sale — photo of the new product line, headline should lead with the
> discount.

or, to test a headline variant of something that already exists:

> Cut a variant of 12a with the headline "Spring colours, *half price*."

Claude reads your Brand Kit and its linked Knowledge page, picks or uploads a photo, writes the
copy against your kit's rules, and renders a preview to check by eye before calling it done. Under
the hood it's calling these MCP tools in sequence: `get_brand_kit`, `upload_creative_photo`,
`create_creative`, `render_creative`, `preview_creative`, and — once you're ready to publish —
`attach_creative_to_post`. `get_post_status` confirms the Post picked it up. `get_brand_kit`
answers with a `configured` flag — when it's false, Claude sets up the kit itself with
`update_brand_kit` and `upload_brand_image` before writing any copy, rather than rendering against
an empty kit. `list_creative_media` lists what's already in your photo/video/audio library, so
Claude can reuse an existing upload instead of asking you for a new one.

Display ids like `12a` (and lettered variants `12b`, `12c`, …) work everywhere you'd type a
Creative's id — in a prompt, in the MCP tools and in the CLI (`conductor creative render 12a`).

### b. In the web app

Go to **Marketing → Creatives → New creative**. It opens straight into the Creative editor:

- **Name**, **Brand kit** (defaults to your workspace default), **Photo** — opens a picker over
  your project's photo library. To upload a new one, fill in **Source** (a URL or a short
  provenance note) and **Licence**, tick **This image is AI-generated** if it is, then pick the
  file. Conductor reads the file's own pixel dimensions; a photo whose long edge is under 2160px
  still uploads but comes back with a warning — that's advisory, not a refusal.
- **Layout** and **Theme** — the layout options and which themes each supports come from your
  project's Creative registry.
- **Headline**, **Body**, **Caption**, **Alt text** — caption is the text meant for the Post, never
  rendered onto the artwork; alt text describes the photo for accessibility.
- **Live preview** on the right shows every enabled placement re-rendering as you type — no network
  round trip. Drag directly on a frame to set that placement's focal point.
- **Extra placements** — opt this Creative into a placement beyond your kit's defaults.
- **Readiness** — a checklist of what still blocks this Creative from being called done: caption,
  alt text, the photo being checked and provenanced, and the Creative's own state.
- **Advanced** (collapsed by default) — **Lockup**: plain or chip (a white-pill logo lockup, for
  busy photography); per-placement **layout overrides** for the stacked layout's photo band height
  and the 9:16 panel's bottom safe-zone clearance, in pixels. Leave a field blank to keep the
  layout's own default.

**Save** writes the Creative. **Save as variant** copies the photo, layout, and body into a new
lettered sibling with its own headline — this is how you set up a hook experiment (see below).

**Delete**, in the header's `…` menu, permanently removes a Creative — refused if any of its
renders is still on a Post (remove it from the Post first) or, for a family root like `12a`, while
its lettered variants (`12b`, `12c`, …) still exist. A photo in the picker can be deleted the same
way, refused while any Creative still uses it.

The web editor never renders final images itself — that always happens from Claude Code/Desktop or
the CLI (`conductor creative render 12a`). The **Renders** panel on the editor page lists what's
been rendered and shows both the exact prompt and the CLI command, ready to copy, for whenever you
want a fresh one.

## 3. Put it on a Post

Once a Creative has a successful render, get it onto a Post one of three ways:

- **Use in Post**, on the Creative editor page — pick an existing Draft Post, or start a new one.
- **From a creative**, in a Post's own media panel — pick any Creative that has a render.
- The `attach_creative_to_post` MCP tool, if you're already working through Claude Code/Desktop.

All three do the same thing: copy the render's frames into the Post's media and set each
destination by placement, for every destination that hasn't chosen its own custom media:

| Placement | Goes to |
|---|---|
| `9:16` | TikTok, and an Instagram destination set to Reel |
| `4:5` | an Instagram destination set to Feed |
| `1:1` | a Facebook destination set to Feed |
| `story` | any destination set to Story |

A destination that already picked its own media by hand is left alone. If you add a new destination
to the Post *after* attaching a Creative, run **Use in Post** again — otherwise that new destination
inherits the Post's whole shared media pool rather than just the frame shaped for it, and Instagram
in particular will block a feed target that ends up with a 9:16 frame.

From here, submit the Post for review the same way you always have — see
[docs/publishing.md](publishing.md) for the approval gate and how publishing works.

## 4. Variants and hook experiments

Cut a lettered variant (`12b` from `12a`) with its own headline — same photo, layout, and body,
different hook. Publish both under similar conditions (same time of day, same platform) so the
comparison is fair, then open **Start experiment** on either variant's editor page and choose a
metric (views, engagement rate, or average view %) and a window in hours (72 by default).

Conductor checks in on its own once every variant has published and reached its window — you can
also press **Check now**. The winner is whichever variant has the higher average view percentage,
when every variant reports one; otherwise it's whichever has the higher value of the metric you
chose. An exact tie among the leaders settles the experiment as inconclusive.

**Confirming a winner is a deliberate step, never automatic.** Once an experiment is decided, a
button appears to add the winning variant's headline to the Brand Kit's approved lines — nothing
happens until you click it.

Performance shows up in two places: the **Performance** panel on a Creative's own editor page (its
whole lettered family, side by side), and **Marketing → What's working → Top creatives**, which
ranks your best-performing variants across the whole project.

## 5. Video

A **Clip** creative uses a finished video as-is — no brand layout, no headline/body rendered onto
it. Upload video from any source (a phone recording, stock footage, something an AI generator
produced) into the same media library the photo picker uses; tick **This video is AI-generated**
when it is, same as a photo. Conductor reads the file's own width, height, duration, and (best
effort) whether it has an audio track, and grabs a poster frame automatically — nothing to fill in
by hand. **Motion** is a branded animated video: your Still creative's layout, theme, photo,
headline, body and CTA, played out over a few seconds with an animated background and optional
audio.

### Make a Clip creative

1. **Marketing → Creatives → New creative**, then set **Kind** to **Clip**. This swaps the
   photo/layout/theme/headline/body fields and the live preview boards for the Clip media section —
   name, caption, alt text, and state stay exactly where they were.
2. **Default** — the video used for every placement that doesn't have its own file below it.
   Conductor works out which placement it best serves by matching its aspect ratio to the closest
   one your project supports (9:16, 4:5, 1:1, or 16:9) — shown under the video once picked.
3. **Per-placement overrides** — 9:16, 4:5, 1:1, and 16:9 each take their own optional video, for
   when one clip doesn't crop well to every shape (a vertical TikTok cut and a separate landscape
   YouTube cut of the same spot, say). A placement with its own video ignores the default entirely.
4. **Caption** and **alt text** — alt text is optional for video (a warning, not a block) since most
   platforms don't show it on video the way they do on an image; caption still runs your Brand Kit's
   copy rules exactly as it does for a Still creative.
5. **Readiness** requires a caption and at least one clip (default or per-placement) before a Clip
   creative can go Ready — same idea as a Still creative's photo-and-headline requirement, just
   video-shaped.

### Make a Motion creative

1. **Marketing → Creatives → New creative**, then set **Kind** to **Motion**. This keeps every
   Still field — brand kit, photo, layout, theme, headline, body, caption, alt text, extra
   placements, lockup/advanced overrides — and adds a **Motion** panel and an **Audio** panel below
   them. There's no Sequence panel for Motion: it's one animated frame, not a set of story beats.
2. **Preset** picks how the headline/body/CTA animate in: **Fade up** (rises into place), **Word by
   word** (the headline builds in one word at a time), **Accent pop** (the headline fades in, then
   your `*accent phrase*` pops), or **None** (everything visible immediately — useful when you want
   the background motion alone to carry it).
3. **Duration** is 3–60 seconds (default 8).
4. **Background** is either your **Photo** — animated with a slow **zoom in/out** or **pan
   left/right** (or **None**) over the whole runtime — or a **Clip**, picked from the video library
   (same picker as a Clip creative's media, locked to video) with a **clip start** (in seconds) for
   where playback begins.
5. **End card** (on by default) holds the full composition — headline, body, CTA — for the last 2
   seconds, regardless of preset, so the video never ends mid-animation.
6. **Audio** source is **Clip sound** (only offered when the chosen background clip actually has an
   audio track), a **Music track** (picked from the audio library, with a play control to audition
   it before you commit), or **None**. A track's **volume** and **fade out** (seconds, at the end)
   only apply when a track is selected. Mind your track's licence — the same field a Clip's video
   source/licence uses is there for audio too; don't use a track you don't have the rights to.
7. The live preview plays the animation right there in the editor — play/pause and a scrub bar
   under the boards move every placement together, and a clip background plays inline. The preview
   never plays audio out loud (a label says so); use the Audio panel's own play control to check a
   track. If your system has "reduce motion" turned on, the preview starts paused instead of
   autoplaying.
8. **Readiness** requires the same things a Still creative does (photo, headline, caption) plus a
   background clip when you chose one, and a track when your audio source is a track.

### Render it

Rendering a Clip is instant — there's no local Playwright job, because there's nothing to draw: the
server just copies your uploaded clip(s) into one render frame per placement they cover. Do it
however's convenient:

- **Prepare for posting**, in the Renders panel on the Creative's editor page — a plain button, web
  only, no Claude Code needed.
- From Claude Code/Desktop or the CLI, the same `render_creative` tool and `conductor creative
  render <id>` command a Still creative uses — it recognizes a Clip and renders it the fast way.

**Motion renders locally**, same as a Still creative — there's no "Prepare for posting" button for
it, because drawing and encoding each frame takes real compute. From Claude Code/Desktop's
`render_creative` tool or `conductor creative render <id>` on the CLI, expect roughly 20 seconds per
placement. The Renders panel shows each finished placement as a playable MP4 with its duration once
it's done.

### Put it on a Post

**Use in Post** works the same as for a Still creative, and maps video frames by placement — a
Motion render's MP4s go through the exact same mapping a Clip's videos do:

| Placement | Goes to |
|---|---|
| `9:16` | TikTok, Instagram and Facebook destinations set to Reel, YouTube when there's no `16:9` video (it goes out as a Short), and a Facebook destination set to Feed when there's no `1:1` video (Facebook publishes a lone Page video as a Reel, vertical recommended) |
| `4:5` | an Instagram destination set to Feed |
| `1:1` | a Facebook destination set to Feed |
| `16:9` | YouTube, and a Facebook destination set to Feed only when there's neither a `1:1` nor a `9:16` video |

A Reel or Story destination takes exactly one video — Conductor never sends it more than one frame.
Keep an eye on each platform's own duration limits (TikTok, Reels, and Shorts are all built for
short vertical video; YouTube's own limits depend on the destination format) — Conductor flags a
clip over about three minutes as a heads-up when you upload it, but doesn't refuse it.

## 6. Troubleshooting

**"headline needs exactly one accent phrase (found 0 asterisks)"** (or 2, or 3) — your Brand Kit
requires an accent phrase and your headline has the wrong number of `*asterisk*` pairs. Wrap
exactly one phrase.

**A copy rule refused the save** — the message shown is the rule's own message, configured on your
Brand Kit under Settings → Brand → Rules. Fix the flagged field and save again, or use **Test a
line** on the Brand settings page to check a headline before you commit to it.

**"This Creative changed elsewhere"** — someone else (or another render) saved a newer version
while you were editing. Click **Reload** to pick up the latest version, then reapply your change;
Conductor never silently overwrites a concurrent edit.

**A photo warning about being under 2160px** — the photo still uploaded and can still be used; this
is a quality heads-up, not a block. Swap in a higher-resolution source if the platform crop looks
soft.

**No browser found for rendering** — rendering needs a local Chromium-family browser. Install
Google Chrome or Microsoft Edge, or run `npx playwright install chromium` once, then try again.

**A render shows a "stale" badge** — the Creative has been edited since that render ran. Re-render
to pick up the latest changes before attaching it to a Post.

**Preview image too large to show inline** — Claude Code/Desktop's `preview_creative` tool inlines
images up to about 1 MB; a larger contact sheet comes back as a URL to open directly instead of
failing.

**Instagram blocked a target for its aspect ratio** — Instagram feed images must fall between 4:5
and 1.91:1. This usually means a destination inherited the wrong frame from the Post's shared
media rather than the one shaped for it — see the note in "Put it on a Post" above about re-running
Use in Post after adding a destination.
