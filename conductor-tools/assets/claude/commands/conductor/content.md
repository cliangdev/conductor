---
name: conductor:content
description: Run a small team of agents that brainstorm competing short-form social post ideas, pick one with a bias-resistant judging method, write the script and visual direction, and render a local draft. You approve at the preview; only then is the Creative uploaded to Conductor. Never creates a Post.
argument-hint: "[request] [--quick]"
allowed-tools: mcp__conductor__*, Agent, AskUserQuestion, Skill, Read, Write, Glob, Grep, WebFetch
---

# /conductor:content

One command from "I need a post for X" to a committed Creative (or, for an idea that needs filming,
a shoot-ready script). The team chooses the idea itself; the only point where it stops for you is the
final draft preview. This command gathers the request and hands off to the `conductor-content-studio`
skill (`Skill(skill: "conductor-content-studio")`), which runs the stages. Don't re-derive what that
skill covers.

## Trigger

`/conductor:content "<request>"`, optionally with `--quick`, or a request to have a team come up
with and make a short-form social post.

- The request is free text: what the post is for, the platform, any line it must say.
- `--quick` runs a lighter version: two ideators, no finalist drafts rendered before the judge
  decides. Pass it through to the skill.

## Step 1 — Check the brand

Read `references/brand-kit-setup.md` in the `conductor-creative` skill
(`.claude/skills/conductor-creative/references/brand-kit-setup.md`, in this project or under `~/.claude`)
and follow it: check the Brand Kit, and offer to set it up if it has never been configured. Then
continue with Step 2. The team writes copy against the kit's rules and approved lines, so an
unconfigured kit makes for generic ideas; say so if the person skips setup.

## Step 2 — The request

If a request came with the command, keep it as given and go to Step 3.

If not, ask one short question with `AskUserQuestion` (one batch, not an interview):

```json
{
  "questions": [{
    "question": "What should the post be for? Include the platform and any line it must say.",
    "header": "Request",
    "options": [
      {"label": "Describe it", "description": "Free text: the goal or topic, the platform (TikTok, Reels, Shorts, Facebook), any must-say line"},
      {"label": "Surprise me", "description": "Pick the strongest idea from what the workspace knows and what has worked"}
    ],
    "multiSelect": false
  }]
}
```

Take the person's free-text answer (their "Other" reply) as the request. For "Surprise me", the
request is "choose the best opportunity from the context" and the skill's strategist decides the
platform from what has worked.

## Step 3 — Hand off to the skill

Call `Skill(skill: "conductor-content-studio")` with the request and the `--quick` flag if it was
given, and follow it from there: it checks for an unfinished run, snapshots context, runs the
ideators and judge, renders finalist drafts, and ends at the preview-and-approve gate.

Nothing is saved to Conductor until the person approves the final draft. A run never creates a Post;
the skill's closing message names the next steps.
