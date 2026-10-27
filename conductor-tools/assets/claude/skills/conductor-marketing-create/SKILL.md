---
name: conductor-marketing-create
description: Makes the artwork for a social post through the Conductor MCP tools, rendered locally as a draft and approved before anything is saved. Direct mode, when a headline, photo, clip or finished images are in hand: make an ad, short video, slideshow, carousel or story sequence, post a clip as-is, write a headline variant, or set up the brand (Brand Kit). Team mode, when there is only a goal: a team of subagents brainstorms, judges, scripts and directs a short-form post and renders a draft. Ends at a committed Creative; never creates a Post. Entry point: /conductor:marketing create.
user-invocable: true
allowed-tools: mcp__conductor__*, Agent, AskUserQuestion, Skill, Read, Write, Glob, Grep, WebFetch
---

# Conductor Marketing Create

Produces the artwork for a Post: upload-ready media at every placement a workspace's Brand Kit
enables, either from a concept the person already has (Direct) or from a goal the team turns into
one (Team). This is the front half of Conductor's marketing pillar; `conductor-marketing-publish` takes the
committed Creative from here through review and out to each destination. You make the artwork; you
do not post it.

## Rules that hold in both modes

- **Brand kit first.** A workspace may have several kits. If the person named a brand or kit, call
  `get_brand_kit` with that `kitId`. Otherwise call it with no `kitId`; if the response has a
  non-empty `otherKits`, ask once with `AskUserQuestion` which kit to use: the default first, labelled
  as the default, then up to 3 others (up to 4 options; "Other" covers the rest by name). Pass the
  chosen kit's id as `brandKitId` on every `preview_creative_draft` and `create_creative` call for
  the run (the default kit needs none), and take the Knowledge page and copy rules from that same kit. If the chosen kit's
  `configured` is `false` the brand has never been set up: follow `references/brand-kit-setup.md`
  and offer setup before making anything. Copy and visuals come from the Brand Kit and its Knowledge
  page, not from invention. For slideshows the *style source* (native, or a kit's `slideshowStyle`)
  is a separate choice: that kit's id goes only to `compose_slideshow`, never onto the Creative
  unless it is also the brand kit (`references/slideshow-formats.md`, "Style").
- **Draft first, save after approval.** `preview_creative_draft` renders on this machine and writes
  nothing to Conductor. Only after the person has seen the draft and approved it does
  `commit_creative_draft` upload the media, save the Creative and run the full render. Never commit
  a draft the person has not seen and approved.
- **A run never creates a Post.** It ends at a committed Creative (or a shoot script). Hand off to
  `conductor-marketing-publish` for the Post, scheduling and review.

## Which mode

| The person has | Mode | Read |
|---|---|---|
| A headline, photo, clip or finished images, an existing Creative to vary, wants the brand set up, or wants a kit style made from slides they like ("Make a kit style from slides") | **Direct** | `references/direct.md` |
| A goal or topic ("a post about X for Y") and wants the idea chosen for them | **Team** (honours `--quick`: two ideators, no finalist renders) | `references/team-run.md` |

Infer the mode from the request. A concrete headline, photo, clip or existing Creative (including a
headline variant or brand setup) is Direct. A goal or topic ("a post about X for TikTok") is Team.
`--quick` applies to Team only. If it is unclear, ask once with `AskUserQuestion` (single select),
"What are we making?", with two options: "I have the headline/photo/clip" (Direct) and "Come up with
an idea" (Team). Team mode finishes through the Direct flow's
preview, check and commit steps (S6 reads `references/direct.md`).

## References

- `references/direct.md`: the Direct flow (read the brand, angle, headline, photo, preview, critique, commit), sequences, video, finished images, short videos, music, cleanup.
- `references/team-run.md`: the Team flow (run folder, stages S0 to S6, FILM ideas, final message, failure handling).
- `references/brand-kit-setup.md`: check the Brand Kit and set it up from a website, a brand doc or direct answers; "Make a kit style from slides" (Direct mode) turns slides the person likes into a slideshow style on a new or existing kit.
- `references/hooks.md`: writing and scoring headline candidates.
- `references/voice.md`: making every line read like a person posted it, with the self-check every
  hook, card and caption passes (both modes).
- `references/layout-guide.md`: choosing a layout, resolution and safe zones.
- `references/design-critique.md`: the six-criterion score applied to every draft before it is shown.
- `references/realism-check.md`: the check every generated or library photo passes.
- `references/slideshow-formats.md`: native slideshow spec, style (native default, kit style, one-off style), archetypes and text rules for organic posts.
- `references/rubric.md`: the judging rubric the creative director uses (Team).
- `references/capabilities.md`: what Conductor can render, and what needs filming (Team).
- `references/platform-specs.md`: per-platform formats, lengths and limits (Team).
- `references/idea-template.md`, `script-template.md`, `shotlist-template.md`: the fixed formats the team's agents write in (Team).
- `references/compliance-checklist.md`: pre-publish checks for disclosure, AI label, music, claims, third-party material, accessibility (Team).
- `references/publish-pack-template.md`: the publish pack `conductor-marketing-publish` later pre-fills a Post from (Team).

## Failures that are silent

Automated checks are real but incomplete. These are the ones worth looking for by eye:

- **A photo with text, a logo or a claim burned into its pixels** passes every copy-rule and render
  check, because none of them read pixels for meaning. Open and look at every photo.
- **A generated image depicting a product, result or claim that is not real** (a made-up app screen,
  a before/after, an invented package) passes every check. Only looking catches it.
- **A structurally valid render is not necessarily a good one.** The checks cover spill, safe zones,
  fonts and contrast, never whether the headline reads at a glance or the copy sits on a face. The
  design critique exists for this gap.
- **A Brand Kit with no copy rules, or no accent-phrase requirement, never fails a save on that
  account.** That is permissive by design, which makes the model the only check on brand voice until
  someone configures rules.
- **A draft is not a saved Creative.** A Creative or media id does not exist until
  `commit_creative_draft` runs; do not hand a draft's folder to `attach_creative_to_post` or a Post.
- **A stale write is refused, not overwritten.** `update_creative` requires the current `version`; a
  409 names the conflict. Reread with `get_creative` and reapply, do not retry blind.
- **Rejected copy stays rejected.** Copy rules with ids starting `banned-` are lines or visuals the
  person rejected earlier; never reuse them.

## Related

- `conductor-marketing-publish`: schedules and tracks the Post the artwork attaches to, through review and out
  to each destination. This skill hands off to it, never the reverse.
- `conductor-marketing-report`: the whole-funnel marketing report; its next actions can feed new
  posts made here.
