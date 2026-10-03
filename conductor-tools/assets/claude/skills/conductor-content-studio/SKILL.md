---
name: conductor-content-studio
description: Runs a small team of subagents (strategist, three ideators, creative director, scriptwriter, art director) that brainstorm competing short-form social post ideas, pick one with a bias-resistant judging method, write the script and visual direction, and render a local draft. The person approves at the preview; only then is the Creative uploaded to Conductor. Ideas are either RENDER (a still, carousel, story sequence or motion text that Conductor can render) or FILM (needs real filming, so the output is a shoot-ready script). A run ends at a committed Creative or a shoot script and never creates a Post. Use when asked to come up with, choose and make a short-form post (TikTok, Reels, Shorts, Facebook) from a goal rather than from a given headline, or via /conductor:content. For a headline or photo already chosen, use conductor-creative instead.
user-invocable: true
allowed-tools: mcp__conductor__*, Agent, AskUserQuestion, Skill, Read, Write, Glob, Grep, WebFetch
---

# Conductor Content Studio

Takes a request ("a post about X for Y") to either a committed Creative or a shoot-ready script. The
team chooses the idea itself; the person is asked once, at the final draft preview. You are the
orchestrator and run in the main session. Subagents do the thinking and writing; you do every
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
- **A run never creates a Post.** It ends at a committed Creative (or a shoot script).

Cost: a run is about 8 subagent calls (strategist 1, ideators 3, director 2, scriptwriter 1, art
director 1), and only the strategist and the director use the top model tier. `--quick` runs two
ideators and skips the finalist renders (about 7 calls).

## Run folder and state

`<projectRoot>/.conductor/content-runs/<YYYYMMDD-HHMM-slug>/`, where `projectRoot` is the working
directory's repository root and `slug` is 2 to 4 words of the request. The `.conductor/` folder is
git-ignored. Write files with `Write` (it creates folders). Every path below is relative to the run
folder; give subagents absolute paths.

```
request.md  run.json  brief.md  script.md  direction.md  creative-spec.json  decision.md
context/   brand-kit.json  knowledge.md  performance.md  media.json
ideas/     angle-1.md  angle-2.md  angle-3.md  pool.md
judging/   scores.md
drafts/    finalist-<id>/   (contact sheets from the finalist renders)
shoot/     shoot-script.md  (FILM winner only)
```

`run.json` is how a run resumes. Update it after every stage:

```json
{
  "runId": "20261003-1415-spring-sale", "request": "...", "quick": false, "platform": "...",
  "stages": {"S0": "done", "S1": "done", "S2": "pending", "merge": "pending", "S3a": "pending",
             "S3b": "pending", "S3c": "pending", "S4": "pending", "S5": "pending", "S6": "pending"},
  "idMap": {"I1": {"angle": "story/emotion", "file": "ideas/angle-1.md", "n": 3}},
  "finalists": [], "winner": null, "tag": null, "runnerUps": [],
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

Resolve the reference files once: Glob for `**/conductor-content-studio/references/rubric.md` under
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
   to what bears on the request; keep approved lines verbatim. List every expected page that was not
   found under "Missing pages". A missing page is a gap to record, never a reason to stop.
3. Performance, for `context/performance.md`: `get_marketing_insights` (window `30d`, or `90d` if
   empty) and `list_top_posts` (limit 10). Summarise what the data says; "no performance data yet"
   is a valid result.
4. `list_creative_media` for `IMAGE`, `VIDEO` and `AUDIO` -> `context/media.json` (id, kind, label,
   dimensions or duration, checked, blocked, provenance).

### S1 — Strategist -> `brief.md`

`Agent(subagent_type: "conductor-content-strategist")` with the five context files and `request.md`.

### S2 — Ideators, in parallel -> `ideas/angle-<n>.md`

Three `Agent` calls in ONE message so they run in parallel, `subagent_type:
"conductor-content-ideator"`, with these angles and no knowledge of each other:

1. story/emotion  2. utility/education  3. trend/humor (the only one allowed web search)

Each gets `brief.md`, `context/media.json`, `references/idea-template.md`, and writes 4 ideas.
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

`Agent(subagent_type: "conductor-content-creative-director")`, mode `text-round`. Inputs:
`brief.md`, `ideas/pool.md`, `references/rubric.md`. It scores every idea independently, reasoning
before each score, and names the top 3. Store the finalists in `run.json`.

### S3b — Finalist drafts (you; skipped in `--quick`)

For each `RENDER` finalist, call `preview_creative_draft` with that idea's draft spec (map the
draft-spec block to the tool's fields) and `draftDir` set to `<run>/drafts/finalist-<id>`. Use
`full: false`. Record each draft dir in `run.json`.

- If the validator refuses the copy (accent phrase or a copy rule), fix the copy once yourself and
  retry. If it still fails, mark that finalist unrenderable in `run.json`, keep its text score, and
  move on. It is not dropped.
- A photo id that is not in `media.json`, or a layout the registry rejects, counts as a refusal.
- `FILM` finalists have no render.

### S3c — Creative director, visual round -> `decision.md`

Same agent, mode `visual-round`. Inputs: `brief.md`, `judging/scores.md`, `ideas/pool.md`, the
finalist image paths (`drafts/finalist-<id>/sheet.jpg`; the director views them with Read) and the
rubric. It compares finalists pairwise in both orders, runs the devil's-advocate pass against the
leader, and writes `decision.md`: the winner, why it won, the runners-up and one line per cut idea.
In `--quick` there are no images, so it judges from the text and says so.

Read `decision.md`, then set `winner`, `tag` and `runnerUps` in `run.json`. Do not ask the person
which idea to take; show the decision at the gate.

### S4 — Scriptwriter -> `script.md`

`Agent(subagent_type: "conductor-content-scriptwriter")` with `brief.md`, `decision.md`, the winning
idea from `ideas/pool.md`, `references/script-template.md` and `references/platform-specs.md`.

### S5 — Art director (`RENDER` only) -> `direction.md` + `creative-spec.json`

`Agent(subagent_type: "conductor-content-art-director")` with `script.md`, `context/media.json`,
`context/brand-kit.json`, `references/shotlist-template.md` and `references/platform-specs.md`.
Tell it the supported options: the kit's enabled placements, and the layouts and themes
the creative registry supports (at the time of writing `stacked`, `bleed`, `split` dark only, `card` dark or
light; a refusal from the validator names the valid ones). The spec's keys must be exactly
`preview_creative_draft` inputs. A `FILM` winner skips S5 and goes to the shoot script, below.

### S6 — Preview gate (you; `RENDER` winner)

1. `preview_creative_draft` with the fields from `creative-spec.json`, `full: false`, and no
   `draftDir` (the default location is cleaned up automatically after the commit). Record the
   returned `draftDir` as `drafts.final`. Local files (`photoPath`, `clipPath`, `audioPath`) go in
   as given in the spec.
2. Eye-check the image. Load the `conductor-creative` skill with `Skill(skill: "conductor-creative")` and
   apply its review guidance (step 7 and the silent-failures list): does the headline read at a
   glance, does the accent phrase fall on the words that carry the meaning, is copy on a face, are
   the safe zones clear. Revise by editing the spec yourself and re-previewing, at most twice. If
   it is still wrong, show it honestly and say what is off.
3. Show the person: the draft image, the finalist sheets of the runners-up (view them from
   `drafts/finalist-<id>/sheet.jpg`; say when a runner-up could not be rendered), and a short decision
   summary (the winner and why, the runners-up, what was cut and why).
4. Ask with `AskUserQuestion`, header "Draft", options:
   - **Approve & upload**: if the spec used a local file (`photoPath`, `clipPath`, `audioPath`),
     first ask for its provenance (`source`, `licence`, and whether it is AI-generated) and pass
     them to `commit_creative_draft({draftDir, source, licence, aiGenerated})`. Then
     `get_creative` and read its `readiness`. If provenance or anything else blocking is missing,
     say exactly what and ask for it; never call a Creative done while `ready` is false.
   - **Use runner-up <id>**: set that idea as `winner`, then re-run S4 onward for it (S5 and S6, or the FILM path
     if it is tagged `FILM`). Offer one option per runner-up (up to two).
   - **Request changes**: take the free text. If it is about the copy, send it with the current
     script to the scriptwriter (S4), then the art director (S5); if only about the look, send it to
     the art director. Then re-preview. Do not commit the old draft.
   - **Stop**: upload nothing. Tell the person where the drafts are
     (`drafts.final` and the finalist folders) and that the run can be resumed.

### FILM winner

1. After S4, have the art director write the shoot version: `Agent(subagent_type:
   "conductor-content-art-director")` with `script.md`, `context/media.json`, `context/brand-kit.json`
   and `references/shotlist-template.md`, output `shoot/shoot-script.md`: script, shot list, capture
   settings (9:16, 30 fps, sound on), B-roll, safe zones, and the on-screen text and captions ready to
   paste. It writes no `creative-spec.json` here.
2. Show the shoot script, then ask with `AskUserQuestion` whether to save it as a project doc:
   **Save to project docs** or **Keep local only**.
3. On save, `write_project_doc` at path `Content/Shoots/<runId> <title>` with the file's content;
   verify with `read_project_doc` and record `shootDoc` in `run.json`. Nothing else is uploaded.

## Final message

Keep it short:

- the decision summary: winner, one line on why, the runners-up;
- the Creative display id (and the readiness gaps, if any) or the shoot doc path;
- next steps:
  - make the Post with `/conductor:creative` or the `conductor-publisher` skill (the Creative is
    not on a Post yet, because a run never creates one);
  - to test a runner-up's hook, cut it as a lettered variant of the Creative with `variantOf`;
  - for `FILM`: film it, upload the clip, then run `/conductor:creative` and pick "Video from a
    clip I have".

## Failure handling

- A subagent that fails twice: mark the stage `failed` in `run.json` and tell the person what
  stopped, with the run folder, so it can be resumed.
- MCP tool errors: surface the message as it is; do not work around a validator refusal by changing
  the rule's field or dropping the check.
- If every finalist is unrenderable, run S3c in text-only mode as in `--quick`.
- Never commit a draft the person has not seen and approved. Never call `create_post`,
  `attach_creative_to_post` or any publishing tool.

## Related

- `conductor-creative`: the single-Creative skill whose checklist S6 uses.
- `conductor-publisher`: takes the committed Creative onto a Post.
