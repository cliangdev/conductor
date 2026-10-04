---
name: conductor:creative
description: One entry point for Conductor creative work — checks the workspace brand, offers to set it up if it's never been configured, asks what to make (static ad, short video, an existing clip), gathers the few inputs needed, then hands off to the conductor-creative skill through draft, preview, approval, commit and attach.
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

Read `references/brand-kit-setup.md` in the `conductor-creative` skill
(`.claude/skills/conductor-creative/references/brand-kit-setup.md`, in this project or under `~/.claude`)
and follow it: check the Brand Kit, and offer to set it up if it has never been configured. Then
continue with Step 2.

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
  already on hand before asking for a new photo. A new photo is a local file path (a URL: download it
  to a local file first) — it is only uploaded after the person approves the preview.
- **Video from a clip I have / Post an existing video as-is**: call
  `list_creative_media({kind: "VIDEO"})` first; if nothing fits, ask for a local path (a URL:
  download it first). A clip used as a MOTION background is previewed from the local file too.
- **Test a headline variant**: ask which existing Creative (id or display id like `12a`) and the new
  headline.

## Step 4 — Hand off to the skill

Call `Skill(skill: "conductor-creative")` and follow it from wherever this command's gathering left
off — angle, copy rules, layout, then the draft-first flow: `preview_creative_draft`, look at the
image, ask for approval, and only then `commit_creative_draft`. Don't re-derive what that skill
already covers.

## Step 5 — Preview, approve, commit, attach

Nothing is saved to Conductor until the person approves what they have seen:

1. `preview_creative_draft` with what was gathered (local files go in `photoPath`/`clipPath`/
   `audioPath`; an edit to an existing Creative passes `baseCreativeId`). It renders on this machine
   and returns the image — no Creative is created and nothing is uploaded.
2. Read `passed` and `checks` in the result first. The preview runs the full render's per-placement
   checks (text spill, platform safe zones such as TikTok/Reels' overlay band, contrast, fonts,
   images), so a design that would fail the real render shows up here. If `passed` is false, fix the
   design (shorter copy, `layoutOverrides`, `typeOverrides`, a different layout) and preview again
   **before** showing it or asking anything — never offer Approve & upload while a blocking check
   fails, and tell the person what you changed. (Bigger or smaller text on one placement is
   `typeOverrides`, e.g. `{"9x16": [96]}`, not a layout switch.)
3. Once `passed` is true, look at the image against the skill's checklist, then show it and ask with
   `AskUserQuestion`: Approve & upload / Request changes / Stop. On changes, preview again; on
   Stop, leave it — the draft stays local under `.conductor/drafts/`.
4. On approval, `commit_creative_draft` with the `draftDir`. It uploads the files, saves the
   Creative and does the full render. (A headline variant is previewed with `baseCreativeId` plus the
   new headline, but on approval is saved with `create_creative` and `variantOf` — never commit that
   draft, which would update the original.)
5. Once the skill's readiness check passes, `attach_creative_to_post` if there's already a Post to
   attach it to (ask for the Post's id if not obvious from context). If there is no Post yet, say so
   plainly and name the next step — creating one via the `conductor-publisher` skill — rather than
   leaving the artwork stranded with no stated destination.

When the Creative came from a content-team run, or the person points to a publish pack, its
`publish-pack.json` carries per-destination captions and publish options (disclosure, AI label,
cover frame, alt text) and a first comment. Look for `.conductor/content-runs/*/publish-pack.json`
whose `creativeId` matches the Creative. When you go on to create or edit the Post with the
`conductor-publisher` skill, pre-fill each target's `captionOverride` and options from it, show them
in the confirmation table and let the person change them. Mention the first comment as a manual
step, since Conductor cannot post comments. The Post still goes through the normal review.
