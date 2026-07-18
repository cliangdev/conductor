---
type: schema
title: Finance domain schema
description: Page-type taxonomy, path layout, and body templates for finance knowledge — metrics and spend decisions.
---

# Finance domain schema

This page is the librarian's style guide for everything filed under `finance/`. Read it before writing
any page in this domain — it extends the root [Knowledge Center schema](/_schema.md) (frontmatter
contract, linking, create-vs-edit) with finance-specific types and templates.

## Page-type taxonomy

| `type` | For |
|---|---|
| `metric` | A tracked financial metric's definition, current value/trend, and where it comes from. |
| `spend-decision` | A recorded budget/spend decision, ADR-style, with a review date. |

If a source doesn't clearly fit one of these, prefer the closest match over inventing a new type; raise
the gap in your run summary instead.

## Path layout

- `finance/metrics/` — `metric` pages.
- `finance/decisions/` — `spend-decision` pages.

Use lowercase, hyphenated filenames (e.g. `finance/metrics/gross-margin.md`).

## Body templates

Every page's body follows its type's template below. Keep every section; write "None yet." rather than
dropping one.

### `metric`

`## Definition` · `## Current` (value + timestamp) · `## Source of truth` (link the system of record —
the wiki stores the narrative around a metric, never a shadow ledger of the numbers themselves).

### `spend-decision`

`## Context` · `## Decision` · `## Alternatives considered` · `## Consequences` · `## Review date` (when
this spend should be revisited — a recurring cost with no review date tends to outlive its justification).
Add `status: proposed|accepted|superseded` to frontmatter, same ADR shape as the root schema's
`decision` template.

## Create vs. edit

Same rule as the root schema: edit in place for an update to something a page already covers (the same
metric, the same spend line); create a new page only for a genuinely distinct entity. Check `index.md`
and `search_knowledge` first — a near-miss title is a strong signal an existing page already owns this
entity.
