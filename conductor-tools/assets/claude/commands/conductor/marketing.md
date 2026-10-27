---
name: conductor:marketing
description: One entry point for marketing work. Make the artwork for a social post (from a headline, photo or clip, or from a goal), publish or schedule a post to a connected account, or build the whole-funnel marketing report. Routes to the matching skill.
argument-hint: "[create|publish|report] [request]"
allowed-tools: mcp__conductor__*, Agent, AskUserQuestion, Skill, WebSearch, WebFetch, Read, Write, Glob, Grep
---

# /conductor:marketing

A router. Pick the skill, then hand off; each skill owns its own flow and checks.

1. **Explicit route.** If the first argument is `create`, `publish` or `report`, go to that skill.
2. **Infer from the request:**
   - Making or designing artwork, an ad, a video, a slideshow or carousel, a post idea from a goal, or
     brand setup: `create`.
   - Posting, scheduling or publishing something to a connected account, or checking where a
     scheduled post stands: `publish`.
   - A report, the funnel, growth or acquisition numbers, "how is the app doing", benchmarks: `report`.
3. **No request, or genuinely ambiguous:** ask once with `AskUserQuestion` (single select), "What do
   you want to do?":
   - "Make a post": `create`.
   - "Publish or schedule a post": `publish`.
   - "Marketing report": `report`.
4. **Hand off** with `Skill(skill: "conductor-marketing-<x>")` (`create`, `publish` or `report`),
   passing the rest of the request and any flags through: `--quick` for create, `7d` or `28d` for
   report. Follow the skill from there.
