# Creatives

A Creative is a photo, a headline, and a layout, rendered from your workspace's Brand Kit into
upload-ready artwork for every placement you publish to. You build one once and it comes out the
right size and shape for TikTok, Instagram, Facebook, and anywhere else you've enabled. A rendered
Creative becomes a Post's media — the approval gate, scheduling, and publishing itself all still
happen on the Post, exactly as before. This guide is for making the artwork; see
[docs/publishing.md](publishing.md) for what happens after you attach it.

For how any of this works under the hood, see [docs/creatives.md](creatives.md).

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

- `npm install -g @cliangdev/conductor` (0.22.0 or later), then `conductor login` and `conductor
  init` in your project — see the [conductor-tools README](../conductor-tools/README.md) if you
  haven't set these up before.
- Google Chrome or Microsoft Edge installed, or run `npx playwright install chromium` once.
  Rendering launches whichever it finds first.

Ask Claude something like:

> Make an ad for our spring sale using the conductor-creative skill — photo of the new product
> line, headline should lead with the discount.

or, to test a headline variant of something that already exists:

> Cut a variant of 12a with the headline "Spring colours, *half price*."

Claude reads your Brand Kit and its linked Knowledge page, picks or uploads a photo, writes the
copy against your kit's rules, and renders a preview to check by eye before calling it done. Under
the hood it's calling these MCP tools in sequence: `get_brand_kit`, `upload_creative_photo`,
`create_creative`, `render_creative`, `preview_creative`, and — once you're ready to publish —
`attach_creative_to_post`. `get_post_status` confirms the Post picked it up.

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

## 5. Troubleshooting

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
