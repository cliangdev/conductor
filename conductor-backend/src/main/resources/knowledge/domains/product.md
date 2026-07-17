---
type: schema
title: Product domain schema
description: Page-type taxonomy, path layout, and body templates for product knowledge — features, experiments, feedback synthesis.
---

# Product domain schema

This page is the librarian's style guide for everything filed under `product/`. Read it before writing
any page in this domain — it extends the root [Knowledge Center schema](/_schema.md) (frontmatter
contract, linking, create-vs-edit) with product-specific types and templates.

## Page-type taxonomy

| `type` | For |
|---|---|
| `feature` | A user-facing product feature. |
| `experiment` | A specific product experiment or A/B test — hypothesis through decision. |
| `feedback-synthesis` | A synthesis of user feedback across multiple sources into themes. |

If a source doesn't clearly fit one of these, prefer the closest match over inventing a new type; raise
the gap in your run summary instead.

## Path layout

- `product/features/` — `feature` pages.
- `product/experiments/` — `experiment` pages.
- `product/feedback/` — `feedback-synthesis` pages.

Use lowercase, hyphenated filenames (e.g. `product/features/knowledge-center.md`).

## Body templates

Every page's body follows its type's template below. Keep every section; write "None yet." rather than
dropping one.

### `feature`

`## What it does` (user-visible behavior) · `## How it works` (mechanics, link `engineering/architecture`
pages) · `## Status` (shipped/in-progress + timestamp) · `## Related` (links).

### `experiment`

```markdown
## Hypothesis
What we believed would happen, and why.

## Setup
Who's in the experiment, what varies between arms, how long it ran.

## Metrics
The metric(s) being watched to judge the outcome — link `finance/metric` pages where they overlap.

## Result
What actually happened, with numbers.

## Decision
Ship, iterate, or kill — and the reasoning.
```

### `feedback-synthesis`

```markdown
## Sources
Where this feedback came from (support tickets, interviews, a survey, sales calls) — enough to judge
how representative it is.

## Themes
The recurring patterns, each as its own subsection.

## Quotes
A few representative quotes per theme, attributed where appropriate.

## Implications
What this suggests for the roadmap — link `feature`/`experiment` pages it should inform.
```

## Create vs. edit

Same rule as the root schema: edit in place for an update to something a page already covers (the same
feature, the same experiment); create a new page only for a genuinely distinct entity. Check `index.md`
and `search_knowledge` first — a near-miss title is a strong signal an existing page already owns this
entity.
