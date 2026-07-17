---
type: schema
title: Marketing domain schema
description: Page-type taxonomy, path layout, and body templates for marketing knowledge — campaigns, personas, positioning, competitors.
---

# Marketing domain schema

This page is the librarian's style guide for everything filed under `marketing/`. Read it before
writing any page in this domain — it extends the root [Knowledge Center schema](/_schema.md) (frontmatter
contract, linking, create-vs-edit) with marketing-specific types and templates.

## Page-type taxonomy

| `type` | For |
|---|---|
| `campaign` | A specific marketing campaign — hypothesis through results and learning. |
| `persona` | A target-audience persona. |
| `positioning` | Positioning for a product, feature, or the company overall. |
| `competitor-card` | A profile of a specific competitor. |

If a source doesn't clearly fit one of these, prefer the closest match over inventing a new type; raise
the gap in your run summary instead.

## Path layout

- `marketing/campaigns/` — `campaign` pages.
- `marketing/personas/` — `persona` pages.
- `marketing/positioning/` — `positioning` pages.
- `marketing/competitors/` — `competitor-card` pages.

Use lowercase, hyphenated filenames (e.g. `marketing/campaigns/q3-launch.md`).

## Body templates

Every page's body follows its type's template below. Keep every section; write "None yet." rather than
dropping one.

### `campaign`

```markdown
## Hypothesis
What we expect this campaign to achieve, and why.

## Setup
Channels, audience, budget, timeline.

## Results
What actually happened — reach, engagement, conversion, spend versus plan.

## Learning
What this tells us for next time; link the `positioning`/`persona` pages it confirms or challenges.
```

### `persona`

`## Profile` (who they are, role, context) · `## Goals & pain points` · `## Where they show up`
(channels, communities) · `## Messaging that resonates` (phrases, angles that have tested well).

### `positioning`

`## Target segment` (link the `persona` it's for) · `## Problem` (the pain it solves) ·
`## Differentiation` (why us, not the alternative) · `## Proof points` (evidence backing the claim).

### `competitor-card`

`## Overview` (who they are, what they sell) · `## Strengths` · `## Weaknesses` · `## How we win`
(the angle that works against them specifically).

## Create vs. edit

Same rule as the root schema: edit in place for an update to something a page already covers (the same
campaign, the same competitor); create a new page only for a genuinely distinct entity. Check `index.md`
and `search_knowledge` first — a near-miss title is a strong signal an existing page already owns this
entity.
