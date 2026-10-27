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
- In Claude Code, run `/conductor:marketing create` and describe what you want — e.g. "Make an ad for our
  spring sale, headline leads with the discount," "Make a 9:16 TikTok video from our latest photo
  with the headline *half price*," or "Upload this finished video as a Clip and put it on a new
  Post." In Claude Desktop (or Claude Code), skip the slash command and just ask the same way —
  Claude uses the same Conductor MCP tools either way.
- Not sure what to post? Run `/conductor:marketing create "<goal and platform>"` with a goal instead of a headline and a team of agents proposes,
  judges and drafts an idea for you to approve (see "Let a content team choose the idea").
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

- `npm install -g @cliangdev/conductor` (0.27.0 or later — earlier versions work for creatives but
  don't have the draft preview below, and 0.27.0 adds the content team), then `conductor login` and `conductor init` in your project —
  see the [conductor-tools README](../conductor-tools/README.md) if you haven't set these up
  before. Already set up? Just update the package; skills refresh automatically from 0.25.0.
- Google Chrome or Microsoft Edge installed, or run `npx playwright install chromium` once.
  Rendering launches whichever it finds first.

In Claude Code, run `/conductor:marketing create` and describe what you want. In Claude Desktop (or Claude
Code, if you'd rather skip the slash command), just ask Claude directly — the same MCP tools drive
either flow, e.g.:

> Make an ad for our spring sale — photo of the new product line, headline should lead with the
> discount.

or, to test a headline variant of something that already exists:

> Cut a variant of 12a with the headline "Spring colours, *half price*."

Claude reads your Brand Kit and its linked Knowledge page, picks a photo, writes the copy against
your kit's rules, and renders a **draft** on your machine for you to look at. **Nothing is saved to
Conductor — no Creative, no photo upload — until you approve it.** You'll be asked to *Approve &
upload*, *Request changes* or *Stop*; changes just make another draft. Approving also confirms the photo has no text, logo or claim burned into it; Claude then moves the Creative from DRAFT to READY once nothing else is missing. Under the hood it's calling
these MCP tools in sequence: `get_brand_kit`, `preview_creative_draft` (renders the draft and shows
you the image), `commit_creative_draft` (after you approve: uploads the photo, saves the Creative,
renders the final frames), and — once you're ready to publish — `attach_creative_to_post`. `get_post_status` confirms the Post picked it up. `get_brand_kit`
answers with a `configured` flag — when it's false, Claude sets up the kit itself with
`update_brand_kit` and `upload_brand_image` before writing any copy, rather than rendering against
an empty kit. `list_creative_media` lists what's already in your photo/video/audio library, so
Claude can reuse an existing upload instead of asking you for a new one.

**Where drafts live.** Each preview writes its image and a `draft.json` into
`.conductor/drafts/<timestamp>-<name>/` in your project (git ignores the folder). Open the image
there any time. Drafts you've committed, and any older than 14 days, are deleted automatically the
next time you preview or commit. Editing an existing Creative works the same way — Claude previews
the change first, and only your approval updates the saved Creative. (Posting a finished video as-is
has no artwork to preview, so it goes up directly.)

Display ids like `12a` (and lettered variants `12b`, `12c`, …) work everywhere you'd type a
Creative's id — in a prompt, in the MCP tools and in the CLI (`conductor creative render 12a`).

#### No photo? Have one generated

Ask Claude to generate one ("make me a photo of ..."). It writes the scene, generates the image
and shows you the result; it is used like any local photo, and is recorded as AI-generated
automatically (the AI label applies when you publish). The Conductor skills generate with Codex by
default: install the Codex CLI (`npm i -g @openai/codex`) and run `codex login` with a ChatGPT Plus (or
higher) account; each image takes about a minute and uses part of the weekly allowance. If Codex is not
available, Claude uses an OpenAI API key when you have set one (`conductor config set-openai-api-key <key>`,
billed per image), or asks you. `conductor doctor` shows both routes.
Generated photos contain no text or logos, and never show your real product unless you give Claude a
real photo of it.

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

The web editor never renders final images in your browser. Final renders happen either from Claude
Code/Desktop or the CLI (`conductor creative render 12a`), or by clicking **Render** (see below). The
**Renders** panel on the editor page lists what's been rendered and shows both the exact prompt and the
CLI command, ready to copy, for whenever you want a fresh one.

#### Rendering from the web app

For a Creative that needs a real render (a still, Motion, a carousel with a music track, or a clip with
replaced, mixed or muted sound), the **Renders** panel has a **Render** button. It starts a render on a
**conductor daemon**, so it needs one running on a machine with Docker:

```bash
conductor start
```

- While the run is working you'll see **Rendering…** and a **View run** link. When it succeeds the new
  render appears in the panel like any other.
- **Waiting for a runner** means no daemon has picked the job up yet: start `conductor start` on a machine
  with Docker (log in as a workspace Admin or Creator). It picks the job up on its own. A run nobody
  picks up is given up on after 24 to 48 hours, and you can click **Render** again after that, or after
  any failed run.
- Only one render run per Creative at a time; clicking again while one is active just shows that run.
- **Render failed** shows the error and a link to the run's logs; fix the cause and click **Render** again.
- If your workspace's Cloud Run runtime target is set up for rendering (Settings, AI Providers, Runtime), renders run there and you can skip the daemon; the panel shows "Rendering on <target name>". Otherwise they run on your daemon as above.
- Nothing renders by itself: it only happens when someone clicks. Claude Code and Desktop still render
  locally on your own machine and do not need a daemon.

### Let a content team choose the idea

If you have a goal rather than a headline, run `/conductor:marketing create "<request>"` in Claude Code (a goal selects team mode; a concrete headline, photo or clip selects direct mode), for
example "a post that gets lapsed users back, for Reels" or "a 20-second explainer on our new report,
TikTok". Add `--quick` for a lighter run. Without a request it first asks whether you already have the headline, photo or clip or want an idea; for an idea it asks one short question: what the post
is for, which platform, and any line it must say. It first checks your Brand Kit the same way
direct mode does.

A small team of agents then works through stages, passing files between them rather than
conversation:

1. **Context.** Your Brand Kit, its Knowledge page, `marketing/what-works.md`, persona and positioning
   pages, recent performance and the media library are saved as a snapshot, along with a short
   learning note (see "The learning loop" below). Missing pages are noted, not fatal.
2. **Brief.** A strategist writes the objective, audience tension, must-say and must-avoid lines (from
   your copy rules and approved lines), the assets on hand and what has worked.
3. **Ideas.** Three ideators with different angles (story and emotion, utility and education, trend
   and humor) each write four ideas without seeing each other's.
4. **Judging.** The ideas are shuffled, stripped of their source and given neutral IDs. A judge on a
   different model tier scores each against a seven-criterion rubric (hook strength, audience
   relevance, reads like a person, brand fit, feasibility with assets on hand, novelty, shareability), writing its reasoning
   before each score, and picks three finalists. Each finalist that can be rendered gets a quick local
   draft, and the judge then looks at the actual images, compares the finalists pairwise in both orders
   (a split counts as a tie) and argues against its own leader before naming a winner.
5. **Script and direction.** A scriptwriter writes the hook (landing in the first three seconds),
   timed beats, payoff, call to action and captions; an art director turns that into visual
   direction and the exact spec for the draft renderer.
6. **Publish pack and checks.** Before you see the draft, the team runs a pre-publish checklist and
   writes a publish pack (below).

**One approval point.** The team picks the idea itself and does not stop to ask you. You are asked
once, at the final draft preview: you see the draft, the runners-up's sheets and a summary of why the
winner won and a short pack summary, then choose *Approve & upload*, *Use runner-up*, *Request changes* or *Stop*. Nothing is
uploaded to Conductor until you approve. The run ends at a committed Creative and **never creates a
Post**; make the Post afterwards with the `conductor-marketing-publish` skill. To test a
runner-up's hook, cut it as a lettered variant of the Creative (see section 4).

**The publish pack.** Each run also leaves `publish-pack.md` (for you) and `publish-pack.json` (for
the publisher) in its folder. Per platform it holds the final caption with the search keyword in the
first line and a call to action that names the app, 3 to 5 hashtags, a pinned comment (you post that by hand; Conductor cannot post
comments), a reminder to repost to Stories with a link sticker, the cover frame, alt text for every image or card, where the on-screen text sits, the
sound and its licence, a posting window from your insights (or "no data yet"), and 2 to 3 alternate
hooks, each tagged with a hook type, ready to cut as lettered variants for a hook test. When you make
the Post from the Creative with the `conductor-marketing-publish` skill, it finds the pack and
pre-fills each destination's caption and publish options. You see them in the confirmation table and
can change any of them, and the Post still goes through the normal review.

**Pre-publish checks.** Seven yes/no checks run before you are asked to approve, and the results are
in the pack:

- **Disclosure.** Is anyone paid, gifted, an employee or an affiliate? If so the disclosure goes in the
  first seconds and the caption, and TikTok's `brandContentToggle` ("Branded content", a paid
  partnership) is set. A brand promoting its own product on its own account sets
  `brandOrganicToggle` ("Your brand") instead, which is the default. You are asked one question
  only when the team cannot tell.
- **AI label.** Realistic AI-generated imagery, video or voice sets TikTok `isAigc` (video posts) and
  YouTube `containsSyntheticMedia`; Instagram and Facebook's "AI info" label is turned on in the
  app. Text, motion graphics and real photos are exempt.
- **Music.** Each platform's sound source and licence is named. Business accounts cannot use most
  trending tracks, so the team picks original audio or TikTok's Commercial Music Library (licensed
  for TikTok only). A track you add to any video, slideshow or clip needs its source and licence
  recorded in the library, and the licence has to cover every platform the video goes to. TikTok's
  `autoAddMusic` only applies to photo posts, so leave it off unless you chose it.
- **Claims.** Each factual or product claim must come from the brief, your Knowledge pages or an
  approved line; otherwise it is softened or cut.
- **Third-party material.** Music, footage, logos, memes, likenesses, trademarks and parody of
  another company's product are listed and cleared or removed.
- **Representation and sensitivity**, and **accessibility** (alt text per image or card, and on-screen
  text so the post works with the sound off).

The judge also applies the claim, trademark/parody, sensitivity and disclosure questions as a veto: an
idea that fails one in a way copy cannot fix is dropped and the decision says so. These checks follow
public guidance (the FTC's endorsement FAQ and the TikTok, Meta and YouTube AI-disclosure rules) and
are a safeguard, not legal advice.

**The learning loop.** Each run records its idea's hook type, angle and format. At the start of the
next run, the team reads those tags for every earlier run that has a Creative, together with how that
Creative performed (views, engagement, average view percentage and any hook experiment result), and
writes `context/learning.md`: which hook types and angles did well. The strategist uses it in the
next brief. With no data yet, it says so. Posting a runner-up's hook as a lettered variant is what
turns this into a real comparison.

**Filming ideas.** An idea is either `RENDER`, meaning Conductor can render it (a still, carousel,
story sequence, or motion text over photos or clips you already have), or `FILM`, meaning it needs a
person or real footage. If a filming idea wins, you get a shoot-ready script instead of a draft:
script, shot list, capture settings, B-roll, safe zones, and on-screen text and captions ready to
paste, plus the publish pack (captions, hashtags, disclosure, music) to apply when the clip is posted. You are asked whether to save it as a project doc under `Content/Shoots/`. Once you have
filmed it, upload the clip and run `/conductor:marketing create` with the clip (direct mode).

**Cost.** A run is about 8 subagent calls, and the strategist, the judge and the scriptwriter use the top model
tier (the ideators stay a tier below the judge). `--quick` uses two ideators with three ideas each and skips the finalist renders (the judge
then decides from the text alone), for about 7 calls.

**Where the files live.** Each run keeps everything in
`.conductor/content-runs/<date-time-slug>/` in your project (git ignores the folder): `brief.md`,
the ideas and scores, the finalist sheets, `decision.md`, `script.md`, `direction.md`,
`creative-spec.json`, `publish-pack.md` and `publish-pack.json`, plus `run.json`, which records progress. If a run is interrupted, running the
command again offers to resume it.

## Make an organic slideshow

For a TikTok photo post or an Instagram carousel that promotes your app without looking like an ad,
ask Claude Code for a slideshow, for example "make a slideshow about X" (or let `/conductor:marketing create`
choose the idea; it defaults to a slideshow for organic posts). Claude writes a slide-by-slide plan
and builds the slides on your machine with `compose_slideshow`.

- **Shape.** 5 to 7 slides. Slide 1 is a human tension (never a feature, the app name or a price), the
  middle slides hold what people save or send, the second-to-last shows your app on a real
  screenshot with one plain mention, and the last asks them to save or follow.
- **What you provide.** Photos (or let Claude generate them) and a real screenshot of the app. There is
  no logo, badge or offer on the slides, and no invented numbers: any figure must be real.
- **Approve it.** Claude shows a contact sheet first; nothing is uploaded until you approve. The result
  is a CAROUSEL Creative you attach to a Post as usual.
- **Getting the download.** TikTok and Instagram captions cannot hold a clickable link, so the caption
  names the app ("search <App> in the App Store"); you also pin a comment, keep the link in your bio,
  and repost to Instagram Stories with a link sticker. The publish pack lists these. Turn on the
  platform's branded-content ("your brand") setting, and the AI label if a photo was generated.

**Adding music.** No platform lets you pick a song for a photo post, so a slideshow with music is
sent as a video instead. Ask Claude for "a slideshow with a music track". Claude first looks through
your audio library and reuses a track that fits; a new file is added to the library with its source
and licence first (never mixed into an MP4 somewhere else). The preview then also builds a 9:16
slideshow video on your machine (it needs ffmpeg) and gives you its path to watch before you approve.
Slides last 3 seconds for the first and 2.5 seconds for the rest, with a slow zoom and 0.5-second
cross-fades; ask for different timing or no zoom. Keep it under about 60 seconds, since YouTube
blocks a Short longer than that when it has a track, and Facebook reels run 3 to 90 seconds. When
you attach it to a Post, each destination sends either the photos (silent) or the video: TikTok and
YouTube default to the video, Instagram and Facebook feed to the photos. You can flip a destination
between **photos** and **video** before it publishes; the confirmation table shows which one it
is.

Ads are different: ask `/conductor:marketing create` for a static ad to get a brand-layout ad.

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
produced) into the same media library the photo picker uses (Marketing → Media, which also lists the files on your Posts); tick **This video is AI-generated**
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
6. **Audio** (optional) — keep the clip's own sound (the default), **replace** it with a library
   music track, **mix** a track in underneath it (the music dips while the clip speaks; only offered
   when the clip has sound), or **mute** it. A track has a volume (1 for replace, 0.3 for mix) and a
   fade out, and its source and licence must be recorded before the Creative can go Ready.

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

Rendering a Clip that keeps its own sound is instant — there's no local Playwright job, because
there's nothing to draw: the server just copies your uploaded clip(s) into one render frame per
placement they cover. A Clip with replaced, mixed or muted sound renders outside the server instead
(ffmpeg makes the new MP4), the same way Motion does: use Claude Code/Desktop or the CLI on your machine,
or the web **Render** button (needs a running `conductor start` daemon, see "Rendering from the web app").
Otherwise, do it however's convenient:

- **Prepare for posting**, in the Renders panel on the Creative's editor page — a plain button, web
  only, no Claude Code needed.
- From Claude Code/Desktop or the CLI, the same `render_creative` tool and `conductor creative
  render <id>` command a Still creative uses — it recognizes a Clip and renders it the fast way.

**Motion renders outside the server**, same as a Still creative — there's no "Prepare for posting" button
for it, because drawing and encoding each frame takes real compute. Use the web **Render** button (on a
conductor daemon) or, from Claude Code/Desktop's
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

**"Waiting for a runner" never goes away** — the web **Render** button needs a conductor daemon running
on a machine with Docker (`conductor start`), logged in as a workspace Admin or Creator, with CLI 0.41.0
or later. The run is abandoned after 24 to 48 hours.

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
