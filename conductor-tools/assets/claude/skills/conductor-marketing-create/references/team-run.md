# Team mode: from a goal to a committed Creative

Read when the person gives a goal ("a post about X for Y") rather than a headline, photo or clip. The
shared rules in `SKILL.md` apply. Takes the request to either a committed Creative or a shoot-ready
script. The team chooses the idea itself; the person is asked once, at the final draft preview. You
are the orchestrator and run in the main session. Subagents do the thinking and writing; you do every
Conductor call and every file merge.

## Rules that shape the design

Each exists because of a known failure mode (sources are in `references/rubric.md`).

- **Subagents do not share context.** Hand off through files. Each stage writes its file and
  returns only the path plus at most about 150 words. Never paste a file's content into a prompt
  when its path will do.
- **Flat orchestration.** Only you call `Agent`. Subagents never spawn subagents.
- **Subagents get no MCP tools.** You make every Conductor call (`get_brand_kit`,
  `preview_creative_draft`, `commit_creative_draft`, ...). Their tools are Read, Write and, for the
  trend ideator, WebSearch.
- **Diverge first.** Three ideators with different angles run in parallel and cannot see each other.
- **Judge bias.** The judge is a different model tier, sees anonymized ideas at the same length,
  writes reasoning before scores, compares finalists pairwise in both orders, and argues against its
  own leader.
- **One gate.** The person is asked only at the final draft preview. Do not stop to ask which idea.
  The one exception is S5b's disclosure question, asked only when disclosure cannot be inferred.
- **A run never creates a Post.** It ends at a committed Creative (or a shoot script).
- **Organic posts default to a slideshow.** A `RENDER` idea for an organic post (not an ad) is a
  native-looking slideshow built by `compose_slideshow` and posted as a CAROUSEL; see
  `references/slideshow-formats.md`. STILL and MOTION stay for ideas that really are one still, an
  ad, or a motion piece. A slideshow never carries a logo, badge or offer.
- **Rejected copy stays rejected.** A CTA or offer line on a card or in a caption is opt-in per
  request, never a default. That means a sales line: the kit's `ctaClaim`, a trial, price, plan or
  discount. Naming the app so it can be searched is not one. Approved lines in the knowledge pages are allowed, not required. When the
  person rejects a line, phrase or visual during a run, ask once whether to ban it. On a yes:
  `get_brand_kit`, append a rule to `copyRules` with id `banned-<slug>` for a line or phrase (an
  escaped regex `pattern`, `flags: "i"`, `fields: ["headline", "body", "caption"]`, a `message` that
  names it as rejected and the date) or `banned-visual-<slug>` for a visual (the `message`
  describes the visual, the `pattern` matches its name). `update_brand_kit` replaces the whole list,
  so send the full existing list plus the new rule, then verify with `get_brand_kit`. S0 reads these
  rules into `context/banned.md` on every later run.

Cost: a run is about 8 subagent calls (strategist 1, ideators 3, director 2, scriptwriter 1, art
director 1). The strategist, the director and the scriptwriter use the top model tier; the
ideators stay on the lower tier so the judge never scores its own tier's ideas. `--quick` runs two
ideators and skips the finalist renders (about 7 calls).

## Run folder and state

`<projectRoot>/.conductor/content-runs/<YYYYMMDD-HHMM-slug>/`, where `projectRoot` is the working
directory's repository root and `slug` is 2 to 4 words of the request. The `.conductor/` folder is
git-ignored. Write files with `Write` (it creates folders). Every path below is relative to the run
folder; give subagents absolute paths.

```
request.md  run.json  brief.md  script.md  direction.md  creative-spec.json  decision.md
slideshow-spec.json  (S5, instead of creative-spec.json, when the winner is a slideshow)
image-prompts.json   (S5, only when generation is enabled)
context/   brand-kit.json  knowledge.md  performance.md  learning.md  banned.md  media.json
ideas/     angle-1.md  angle-2.md  angle-3.md  pool.md  winner.md
judging/   scores.md
drafts/    finalist-<id>/   (contact sheets from the finalist renders)
publish-pack.md  publish-pack.json   (S5b; see references/publish-pack-template.md)
shoot/     shoot-script.md  (FILM winner only)
```

`run.json` is how a run resumes. Update it after every stage:

```json
{
  "runId": "20261003-1415-spring-sale", "request": "...", "quick": false, "platform": "...",
  "stages": {"S0": "done", "S1": "done", "S2": "pending", "merge": "pending", "S3a": "pending",
             "S3b": "pending", "S3c": "pending", "S4": "pending", "S5": "pending", "S5b": "pending",
             "S6": "pending"},
  "idMap": {"I1": {"angle": "story/emotion", "file": "ideas/angle-1.md", "n": 3}},
  "finalists": [], "winner": null, "tag": null, "runnerUps": [],
  "tags": {"hookType": null, "angle": null, "format": null},
  "drafts": {"finalist-I3": "<absolute dir>", "final": "<absolute dir>"},
  "creativeId": null, "creativeDisplayId": null, "shootDoc": null
}
```

Stage values: `pending`, `done`, `failed`, `skipped`. The `idMap` is the only place angles are
linked to IDs; it is never shown to the director.

**Resume check (first thing).** Glob `.conductor/content-runs/*/run.json`. If one has a stage that is
not `done` or `skipped` and no `creativeId` or `shootDoc`, ask with `AskUserQuestion` whether to
**Resume** that run (name it and its last finished stage) or **Start a new run**. On resume, skip
done stages and continue from the first pending one.

## Delegation prompt template

Every `Agent` call uses this shape, filled in. Keep it short; the agent's own file holds its role.

```
Objective: <one sentence: what this stage must produce>
Mode / angle: <text-round | visual-round | angle "story/emotion" with N ideas | n/a>
Inputs (read only these): <absolute paths>
Template / rubric: <absolute path to the reference file>
Output: write only <absolute path>
Boundaries: do not call Conductor or any MCP tool; do not read or write other files; follow the
template exactly; <any stage-specific rule>
Return: at most 150 words plus the output path.
```

Resolve the reference files once: Glob for `**/conductor-marketing-create/references/rubric.md` under
`.claude/skills/` in this project, then under `~/.claude/skills/`. Use that folder for every
`references/` path below. A subagent that returns more than 150 words or leaves its file unwritten
has failed the stage: retry once with a one-line reminder, then tell the person.

## Stages

### S0 — Context snapshot (you)

Write `request.md` (the request verbatim, `--quick` if given, the platform if stated). Then:

1. `get_brand_kit` -> `context/brand-kit.json`. If `configured` is false and the person already
   skipped setup in the command, note that in the brief's constraints.
2. Knowledge, for `context/knowledge.md`: `read_knowledge_pages` for the kit's `knowledgePagePath`
   and `marketing/what-works.md`; `search_knowledge` for persona/audience, campaign and positioning
   pages and read the best few (at most 6 pages in all). Paste each page under its path heading, trimmed
   to what bears on the request; keep approved lines verbatim, marked "may use, not required". List every expected page that was not
   found under "Missing pages". A missing page is a gap to record, never a reason to stop.
3. Performance, for `context/performance.md`: `get_marketing_insights` (window `30d`, or `90d` if
   empty) and `list_top_posts` (limit 10). Summarise what the data says; "no performance data yet"
   is a valid result. For the whole-funnel view (acquisition, in-app funnel, benchmarks) `get_marketing_report` exists.
4. `list_creative_media` for `IMAGE`, `VIDEO` and `AUDIO` -> `context/media.json` (id, kind, label,
   dimensions or duration, checked, blocked, provenance).
5. Learning, for `context/learning.md`. Glob `.conductor/content-runs/*/run.json`, skip this run, and
   keep the runs that have a `creativeId` and `tags`. For each, call `get_creative` (the variants'
   attributed performance and the family's hook experiment, with its winner once decided) and read
   its tags (hookType, angle, format). Summarise which hook types and angles did well and which did
   not, using views, engagement rate, average view percentage, 72 h views and any 3-second or
   completion figure the insights expose, and any experiment result. Name what you could not
   measure. With no earlier run or no data, write exactly "No data yet".
6. Banned copy, for `context/banned.md`: list every `copyRules` entry in `context/brand-kit.json`
   whose id starts with `banned-` (the line or visual, the pattern and the message). With none,
   write exactly "No banned copy yet".
7. Every renderable idea needs a photo (or, for MOTION, a clip; for a slideshow's payoff slide, a real
   app screenshot, so ask for one if the media has none); see `references/capabilities.md`. If
   `media.json` has no unblocked image and no clip, ask once with `AskUserQuestion` before ideating:
   **Give a photo or two** (local paths; record them in `context/media.json` as `local` entries the
   art director may use via `photoPath`), **Generate a photo** (record `"generate": true` in
   `context/media.json`; ideas can then be `RENDER` with no library photo, each giving an
   `imagePrompt`, and photos are made with `generate_creative_image`)
   or **Plan for filming** (ideas will be `FILM`). Never let the team design a type-only still; the
   preview refuses it.
   When the library does have usable images, or the request itself asks for generated imagery, set
   `"generate": true` without asking: ideas use a library photo when one fits and give an
   `imagePrompt` only when none does. Generate with Codex by default: pass `useCodex: true` on every
   `generate_creative_image` call without asking (it is cheaper than the API). If Codex fails
   (missing, logged out, or a plan without image generation), say why once and call again without
   `useCodex`: that uses the person's OpenAI API key when one is set. With no key it returns
   `CODEX_CONFIRMATION_REQUIRED`; then ask once per run whether to fix Codex (`codex login`), set a
   key, or skip and use a library photo. If no route works, treat that finalist as unrenderable in
   S3b; at S6, say why and ask for a photo instead of offering Approve & upload.

### S1 — Strategist -> `brief.md`

`Agent(subagent_type: "conductor-marketing-strategist")` with the seven context files (including `context/banned.md`, whose every entry goes into the brief's must-avoid), `request.md`
and `references/capabilities.md` (so the brief states rendering constraints correctly: motion text
over a photo is renderable and needs no filming).

### S2 — Ideators, in parallel -> `ideas/angle-<n>.md`

Three `Agent` calls in ONE message so they run in parallel, `subagent_type:
"conductor-marketing-ideator"`, with these angles and no knowledge of each other:

1. story/emotion  2. utility/education  3. trend/humor (the only one allowed web search)

Each gets `brief.md`, `context/media.json`, `references/idea-template.md`,
`references/capabilities.md` (the `RENDER`/`FILM` test) and `references/voice.md`, and writes 4 ideas.
With `--quick`: only angles 1 and 2, 3 ideas each.

### Merge (you) -> `ideas/pool.md`

Build one pool from all the angle files:

- Give every idea an ID `I1..In` in a shuffled order (not grouped by angle, not in file order).
- Remove angle names and any trace of the source. Put every idea in the identical template from
  `references/idea-template.md`, trimming any field to its word cap so lengths match.
- Record the true mapping in `run.json` `idMap`. Keep the tag (`RENDER`/`FILM`) in the pool.
- Drop an idea that breaks the brief's must-avoid list or copy rules in a way you cannot fix by
  one word, and note the drop in `run.json`.

### S3a — Creative director, text round -> `judging/scores.md`

`Agent(subagent_type: "conductor-marketing-creative-director")`, mode `text-round`. Inputs:
`brief.md`, `ideas/pool.md`, `references/rubric.md`, `references/voice.md`. It scores every idea independently, reasoning
before each score, and names the top 3. Store the finalists in `run.json`.

### S3b — Finalist drafts (you; skipped in `--quick`)

A slideshow finalist has no cheap draft: judge it from its idea text (archetype and slide-by-slide
text) unless its slides can be composed from photos already on hand. In that case write its spec to
`<run>/drafts/finalist-<id>/slideshow-spec.json`, call `compose_slideshow` with `draftDir` set to
that folder and judge from the first slide and the `checks`. For every other `RENDER` finalist, call `preview_creative_draft` with that idea's draft spec (map the
draft-spec block to the tool's fields) and `draftDir` set to `<run>/drafts/finalist-<id>`. Use
`full: false`. Record each draft dir in `run.json`. For a finalist whose draft spec carries an
`imagePrompt`, write the prompt by the prompt-writing rules in `references/realism-check.md` and
call `generate_creative_image({prompt, aspect: "9:16"})` (the largest 9:16 size, so the draft is not
upscaled), run the realism check on 100% crops (regenerating on a fail), and use its
path as `photoPath` (generate only for finalists, not the whole pool).

- If the validator refuses the copy (accent phrase or a copy rule), fix the copy once yourself and
  retry. If it still fails, mark that finalist unrenderable in `run.json`, keep its text score, and
  move on. It is not dropped.
- A photo id that is not in `media.json`, or a layout the registry rejects, counts as a refusal.
- The preview result carries `checks` and `passed` (the full-size safe-zone, spill and contrast
  checks). If a finalist fails a blocking check, fix it once (`layoutOverrides`, `typeOverrides`, a
  shorter body) and re-preview; otherwise keep it and record the failure for the judge.
- `FILM` finalists have no render.

### S3c — Creative director, visual round (text-only with `--quick`) -> `decision.md`

Same agent, mode `visual-round`. Inputs: `brief.md`, `judging/scores.md`, `ideas/pool.md`, the
finalist image paths (`drafts/finalist-<id>/sheet.jpg`; the director views them with Read), the
rubric, `references/design-critique.md` and `references/compliance-checklist.md`. It compares finalists pairwise in both orders, runs
the devil's-advocate pass against the leader (including the veto questions: an unsubstantiated
claim, trademark or parody risk, a representation or sensitivity problem, disclosure needed), and
writes `decision.md`: the winner, why it won, the runners-up, one line per cut idea and the veto
answers. A veto "yes" that copy cannot fix disqualifies an idea. In `--quick` there are no images,
so it judges from the text and says so.

Read `decision.md`, then set `winner`, `tag` and `runnerUps` in `run.json`, and the run's own
`tags`: `hookType` from the winning idea's hook type, `angle` from `idMap` (`story-emotion`,
`utility-education` or `trend-humor`) and `format` from the idea's format. Do not ask the person
which idea to take; show the decision at the gate.

### S4 — Scriptwriter -> `script.md`

First copy the winning idea's block from `ideas/pool.md` to `ideas/winner.md`. Then
`Agent(subagent_type: "conductor-marketing-scriptwriter")` with `brief.md`, `decision.md`, `ideas/winner.md`, `references/script-template.md`, `references/platform-specs.md`,
`references/publish-pack-template.md` and `references/voice.md`. Besides the script it writes the "Numbers and sources" list (every number on a card or in a caption, with its source record) and the Publish copy section: a
caption per platform whose call to action names the app so it can be searched, hashtags, a pinned
comment and 2 to 3 alternate hooks with a hookType each.
For a `MOTION` winner tell it that the render shows one headline and one optional body, animated
by preset, then an end card that holds the final frame: no timed multi-beat on-screen text. Extra
beats belong in a story or carousel sequence, or in the caption. When merging into `publish-pack.json`, append each platform's hashtags to its `captionOverride`
(caption, blank line, hashtags): that field is posted verbatim.

### S5 — Art director (`RENDER` only) -> `direction.md` + `creative-spec.json` or `slideshow-spec.json`

For a slideshow winner the art director writes `slideshow-spec.json` instead of `creative-spec.json`
(and no `image-prompts.json` unless it needs a photo made). Give it `references/slideshow-formats.md`,
`context/media.json`, `script.md`, `references/platform-specs.md`, `references/design-critique.md` and
`references/publish-pack-template.md`. Photos and the app screenshot are paths from `media.json`
(download a library image to a local file first) or the person's local files; generated photos go
through `generate_creative_image` and the realism check first, as below. Every photo then goes
through the resolution gate below. The rest of this stage is
the STILL/MOTION path.

`Agent(subagent_type: "conductor-marketing-art-director")` with `script.md`, `context/media.json`,
`context/brand-kit.json`, `context/banned.md`, `references/shotlist-template.md`,
`references/platform-specs.md`, `references/realism-check.md` and
`references/publish-pack-template.md`. Besides the spec it writes the Publish assets section of
`direction.md`: cover frame, alt text per card, on-screen text placement and the sound source and
licence per platform.
Tell it the supported options: the kit's enabled placements, and the layouts and themes
the creative registry supports (at the time of writing `stacked`, `bleed`, `split` dark only, `card` dark or
light; a refusal from the validator names the valid ones). Also give it `references/layout-guide.md`
and `references/design-critique.md`: it picks the layout per the guide, and never `stacked` or `split`
when 9:16 is enabled. The spec's keys must be exactly
`preview_creative_draft` inputs. Give it `references/capabilities.md` too. The spec's `name` is the
idea's title only: never include the internal `I<n>` ID, which means nothing outside this run. When
`media.json` has `generate: true`, the art director may also write `image-prompts.json`
(`{"photo": "...", "beats": {"2": "..."}}`) next to the spec. Prompts follow the prompt-writing
rules in `references/realism-check.md` and differ in surface, angle and light across the batch. Before S6, call
`generate_creative_image` for each prompt with `aspect: "9:16"` for 9:16 placements (`"4:5"` or
`"1:1"` only when no 9:16 placement is enabled), read the result's `placementFit`, run the realism check
(`references/realism-check.md`, regenerating on a fail) on each image at 100%, and set `photoPath` (or
`sequence[i].photoPath`) in `creative-spec.json` (for a slideshow, the slide's `bg` or `photos[].src` in `slideshow-spec.json`; `beats` keys are slide numbers). A `FILM` winner skips S5 and goes to the shoot
script, below.

**Resolution gate (before S6 renders).** For every photo in the spec, library or generated, compute
the scale per enabled placement and crop, adding the 1.12 video zoom for MOTION (see
`references/layout-guide.md`, "Resolution"). Use `placementFit` for a generated photo, and
`max(W/w, H/h)` from `list_creative_media` dimensions for a library one. Refuse any full-bleed use
above 1.0x. Fix it before rendering: generate at the largest size in that aspect, use a different
photo, or pick a layout that shows the photo framed at or below native size. Never carry an
upscaled photo into S6 with a note.

### S5b — Publish pack and pre-publish checks (you; RENDER after S5, FILM after the shoot script)

Before the gate, so the person sees the settings with the draft. Skip nothing here; a check that
does not apply is answered "not applicable".

1. Run `references/compliance-checklist.md` against `brief.md`, `script.md`, `direction.md` (or the
   shoot script), `creative-spec.json` (or `slideshow-spec.json`) and the provenance in `context/media.json` (`aiGenerated`,
   `licence`).
2. Merge the scriptwriter's Publish copy and the art director's Publish assets, add the compliance
   results, and write `publish-pack.md` and `publish-pack.json` in the shapes of
   `references/publish-pack-template.md`. Real option keys only. The posting window comes from
   `get_marketing_insights` (`byHour`, `byWeekday`) for the platform, or "no data yet".
3. Ask at most ONE `AskUserQuestion`, and only when disclosure cannot be inferred: the request,
   brief or media names a creator, customer, partner or sponsor and the relationship is unclear.
   Question "Is anyone in or behind this post paid, gifted, an employee/affiliate?" with options
   **No, this is the brand's own post**, **Yes, paid partnership**, **Yes, gifted or affiliate**.
   Otherwise assume the brand's own promotional post and set TikTok `brandOrganicToggle`. Paid
   sets `brandContentToggle`; gifted or affiliate sets `brandContentToggle` and adds the
   disclosure to the caption's first line.
4. A claims or trademark check that fails and cannot be fixed by you in one word goes back to the
   scriptwriter once, with the failing line; re-run S4's merge and S5 only if the copy changed. If
   it still fails, show it at the gate as a known issue and never hide it.
5. Fact lint: merge the scriptwriter's "Numbers and sources" into the pack and check each number
   against its source record (checklist item 9). Fix a mismatch before the gate.
6. Banned-copy grep: search `creative-spec.json` or `slideshow-spec.json`, `script.md`,
   `image-prompts.json`, every caption and `publish-pack.md` and `.json` for every banned line,
   phrase and visual in `context/banned.md`, case-insensitive. A hit is fixed (then redo the
   affected stage), never shown. Record the result in the pack's "Banned-copy check".
7. STILL or MOTION: it always shows the kit's `ctaClaim`. If that claim is banned, or the request
   did not opt in to a CTA and the claim is an offer line, stop and ask the person before S6
   renders. A sequence uses `cta: false` on every beat unless the brief opted in.
8. Set `S5b` to `done`. On any later copy change, redo this stage so the pack matches the draft.

### S6 — Preview gate (you; `RENDER` winner)

Resolution gate first: confirm S5's gate passed for every photo in the spec. A photo above 1.0x at
any placement is fixed now, not shown.

1. `preview_creative_draft` with the fields from `creative-spec.json`, `full: false`, and no
   `draftDir` (the default location is cleaned up automatically after the commit). Record the
   returned `draftDir` as `drafts.final`. Local files (`photoPath`, `clipPath`, `audioPath`) go in
   as given in the spec.
   **Slideshow winner:** `compose_slideshow({specPath})` on `slideshow-spec.json`; while `passed` is
   false, fix the spec and compose again. Then `preview_creative_draft({kind: "CAROUSEL", slidePaths,
   caption, slideAltText})` with the returned `slidePaths` (`9x16` for TikTok, `4x5` for Instagram),
   the caption from `publish-pack.json` and the alt text from `direction.md`. Record the preview's
   `draftDir` as `drafts.final`.
   **Music:** a slideshow or clip with music uses `audio` on the Creative, and a track from
   `context/media.json` (`kind: "AUDIO"`, unblocked) is reused before any new file. A new music file
   is uploaded to the library first with `upload_creative_media` (with `source` and `licence`) and
   referenced by `trackId`; never bake music into an MP4 outside Conductor. A slideshow with
   `audio.source: "track"` previews as its slideshow MP4 too (`slideshowVideoPath`), so give the
   person that path to watch along with the sheet, and put the track's licence in the pack's music
   line (`references/compliance-checklist.md` section 3).
2. Eye-check the image. Read `references/direct.md` (same skill) and
   apply its review guidance (step 7 and the silent-failures list), scoring the draft with
   `references/design-critique.md` (the slideshow criteria for a slideshow, the STILL criteria
   otherwise; any criterion below 3 is fixed before anyone sees it). For a STILL: does the headline read at a
   glance, does the accent phrase fall on the words that carry the meaning, is copy on a face, are
   the safe zones clear. For a slideshow: does slide 1 stop the scroll, is the text inside y 16 to 75,
   is the screenshot real, and is there no logo, badge or offer. Also read the result's `checks`/`passed`: while any blocking check fails,
   fix it (`layoutOverrides` for photo height, `typeOverrides` for text size, a shorter body) and
   re-preview **before** showing anything, and never offer Approve & upload while one still fails.
   Revise at most twice beyond that; if it is still wrong, show it honestly and say what is off.
3. Show the person: the draft image, the finalist sheets of the runners-up (view them from
   `drafts/finalist-<id>/sheet.jpg`; say when a runner-up could not be rendered; with `--quick` no
   sheets exist, so show runners-up by title and tag only), and a short decision
   summary (the winner and why, the runners-up, what was cut and why), and a short pack summary: the
   disclosure setting (which toggle, or none), the AI-label setting, the sound and its licence, the
   pinned comment and the Stories link-sticker repost, and the alternate hooks with their types. For each generated photo, add its one-line realism verdict.
4. Ask with `AskUserQuestion`, header "Draft", with exactly these four options. `AskUserQuestion`
   allows at most four, so never add one per runner-up, and **Stop** must always be among them:
   - **Approve & upload**: the question says approving also confirms the photo carries no burned-in
     text, logo or claim (for a slideshow: every number and claim on the slides is real and the
     screenshot is genuine). Ask for provenance (`source`, `licence`, and whether it is AI-generated)
     only for local files the preview lists in `provenance.needed` (a file from
     `generate_creative_image` already carries it), then
     `commit_creative_draft({draftDir, photoChecked: true, source, licence, aiGenerated})` (provenance
     fields only when needed). Then `get_creative` and read its `readiness`. If `state` is the only
     blocking item, `update_creative({creativeId, state: "READY"})` and verify with `get_creative`;
     otherwise say exactly what is missing and ask for it. Never call a Creative done while `ready`
     is false. After the commit, write the Creative's id into `publish-pack.json` as `creativeId` and
     its state into `publish-pack.json` as `creativeState` (and both into `run.json`).
   - **Use a runner-up**: ask a second `AskUserQuestion` listing the runners-up by title (with
     their tag; label one that could not be rendered as such and say why, rather than offering it as
     if it would work). Then set the chosen idea as `winner` and re-run S4 onward for it (S5, S5b and S6,
     or the FILM path if it is tagged `FILM`).
   - **Request changes**: take the free text. If it is about the copy, send it with the current
     script to the scriptwriter (S4), then the art director (S5); if only about the look, send it to
     the art director. Redo S5b, then re-preview. Do not commit the old draft.
   - **Stop**: upload nothing. Tell the person where the drafts are
     (`drafts.final` and the finalist folders) and that the run can be resumed.

### FILM winner

1. After S4, have the art director write the shoot version: `Agent(subagent_type:
   "conductor-marketing-art-director")` with `script.md`, `context/media.json`, `context/brand-kit.json`
   and `references/shotlist-template.md`, output `shoot/shoot-script.md`: script, shot list, capture
   settings (9:16, 30 fps, sound on), B-roll, safe zones, and the on-screen text and captions ready to
   paste. It also writes the Publish assets section there. It writes no `creative-spec.json` here.
2. Run S5b. The shoot script gets the publish pack too (captions, hashtags, pinned comment,
   disclosure, music and its licence) so it can be applied when the clip is posted later; append a
   short "Publish pack" section to `shoot/shoot-script.md`, and leave `creativeId` null in
   `publish-pack.json`.
3. Show the shoot script, then ask with `AskUserQuestion` whether to save it as a project doc:
   **Save to project docs** or **Keep local only**.
4. On save, `write_project_doc` at path `Content/Shoots/<runId> <title>` with the file's content;
   verify with `read_project_doc` and record `shootDoc` in `run.json`. Nothing else is uploaded.

## Final message

Keep it short:

- the decision summary: winner, one line on why, the runners-up;
- the Creative display id (and the readiness gaps, if any) or the shoot doc path;
- `publish-pack.md` (captions, hashtags, pinned comment, disclosure and AI-label settings, sound,
  alternate hooks); say the publisher will pre-fill the Post from `publish-pack.json`, and that the
  pinned comment, the link in bio and the Stories repost with a link sticker are manual steps;
- next steps:
  - make the Post with the `conductor-marketing-publish` skill (the Creative is
    not on a Post yet, because a run never creates one);
  - to test a runner-up's hook, cut it as a lettered variant of the Creative with `variantOf`;
  - for `FILM`: film it, upload the clip, then run `/conductor:marketing create` and pick a clip I have.

## Failure handling

- A subagent that fails twice: mark the stage `failed` in `run.json` and tell the person what
  stopped, with the run folder, so it can be resumed.
- MCP tool errors: surface the message as it is; do not work around a validator refusal by changing
  the rule's field or dropping the check.
- If every finalist is unrenderable, run S3c in text-only mode as in `--quick`.
- Never commit a draft the person has not seen and approved. Never call `create_post`,
  `attach_creative_to_post` or any publishing tool.

## Related

- `references/direct.md`: the single-Creative flow whose checklist S6 uses.
- `references/slideshow-formats.md`: the slideshow spec, archetypes and text rules.
- `conductor-marketing-publish`: takes the committed Creative onto a Post, pre-filled from `publish-pack.json`.
