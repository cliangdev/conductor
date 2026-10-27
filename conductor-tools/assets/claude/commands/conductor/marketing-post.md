---
name: conductor:marketing-post
description: Make the artwork for a social post. Give it a headline, photo or clip and it renders a draft for your approval (Direct), or give it a goal and a team of agents comes up with, judges and makes the post (Team). Nothing is saved to Conductor until you approve the preview, and it never creates a Post.
argument-hint: "[request] [--quick]"
allowed-tools: mcp__conductor__*, Agent, AskUserQuestion, Skill, Read, Write, Glob, Grep, WebFetch
---

# /conductor:marketing-post

Entry point for the `conductor-marketing-post` skill. Pick the mode, then hand off; the skill owns
the brand check, the flow and the approval gate.

1. **No request given:** ask once with `AskUserQuestion` (single select), "What are we making?":
   - "I have the headline/photo/clip": Direct mode.
   - "Come up with an idea": Team mode.
2. **Request given:** infer the mode. It names a concrete headline, photo, clip or existing Creative
   (including a headline variant or brand setup): Direct. It states a goal or topic ("a post about X
   for TikTok"): Team.
3. Call `Skill(skill: "conductor-marketing-post")` with the mode, the request and `--quick` if given
   (Team only), and follow the skill from there.
