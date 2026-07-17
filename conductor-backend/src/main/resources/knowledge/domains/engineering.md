---
type: schema
title: Engineering domain schema
description: Page-type taxonomy, path layout, and body templates for engineering knowledge — architecture, runbooks, postmortems, decisions, integrations.
---

# Engineering domain schema

This page is the librarian's style guide for everything filed under `engineering/`. Read it before
writing any page in this domain — it extends the root [Knowledge Center schema](/_schema.md) (frontmatter
contract, linking, create-vs-edit) with engineering-specific types and templates.

## Page-type taxonomy

| `type` | For |
|---|---|
| `architecture` | A system/service/module's design — two templates, backend vs frontend (below). |
| `runbook` | Operational procedure for a recurring task or incident response. |
| `postmortem` | Retrospective on an incident — impact, timeline, root cause, follow-ups. |
| `adr` | An engineering decision record (architecture/tech-stack calls). Files under `engineering/decisions/` — the cross-cutting root `decisions/` is for non-engineering calls. |
| `integration` | A third-party integration or connector's setup/behavior. |

If a source doesn't clearly fit one of these, prefer the closest match over inventing a new type; raise
the gap in your run summary instead.

## Path layout

- `engineering/architecture/` — `architecture` pages.
- `engineering/runbooks/` — `runbook` pages.
- `engineering/postmortems/` — `postmortem` pages.
- `engineering/decisions/` — `adr` pages.
- `engineering/integrations/` — `integration` pages.

Use lowercase, hyphenated filenames (e.g. `engineering/architecture/knowledge-center.md`).

## Body templates

Every page's body follows its type's template below. Keep every section; write "None yet." rather than
dropping one. Diagrams are Mermaid in a fenced ` ```mermaid ` block (the UI renders them natively).

### `architecture` — backend (diagram-first, C4-inspired)

```markdown
## Purpose
One paragraph: what this component/service exists to do.

## Diagram
A Mermaid `flowchart` of the key components and their interactions — 5–10 boxes max, C4
container/component altitude. Every box is labeled with name + role ("Scheduler<br/>(claims batches)");
every arrow is labeled with the interaction ("dispatches run"). Zoom out, not in: if it needs more than
10 boxes, split into linked child pages.

## Components
One bullet per box in the diagram: name — responsibility, key classes/paths.

## Interactions
The non-obvious flows: ordering, transactions, failure handling.

## Key decisions
Bullets linking to /engineering/decisions/*.md pages where they exist.
```

### `architecture` — frontend

```markdown
## Purpose
One paragraph: what this surface/app exists to do, and who uses it.

## Route map
The URL/route tree this surface owns — one line per route, noting what it renders and any route-level
guards (auth, project scope).

## Component tree
A Mermaid `flowchart` (or nested-bullet list if simpler) of the page's major components, parent to
child — name + one-line responsibility per node. Zoom out: split into a linked child page past ~10 nodes.

## State & data flow
Where state lives (server components, client state, context, cache) and how data moves in — API calls,
polling, websockets — and back out (mutations, optimistic updates).

## Design-system dependencies
Shared components/tokens this surface leans on (link `docs/design-system.md` sections or component
paths) — call out anything that deviates from the system and why.
```

### `runbook`

```markdown
## Trigger
When to run this — an alert, a symptom, a scheduled task.

## Steps
Numbered, copy-pasteable where possible (commands, dashboard links).

## Verification
How to confirm the procedure worked.

## Rollback
How to undo it if something goes wrong partway through.
```

### `postmortem`

```markdown
## Impact
Who/what was affected, how badly, for how long.

## Timeline
Timestamped sequence of detection, escalation, mitigation, resolution.

## Root cause
The actual failure mechanism, not just the trigger — one layer deeper than "what set it off".

## Action items
Bullets with an owner and status each; link the tracking Work Item where one exists.
```

### `adr`

`## Context` · `## Decision` · `## Alternatives considered` · `## Consequences`. Add
`status: proposed|accepted|superseded` to frontmatter — same ADR shape as the root schema's `decision`
template, scoped here to architecture/tech-stack calls specifically.

### `integration`

`## What it connects` · `## Setup` · `## Behavior`.

## Create vs. edit

Same rule as the root schema: edit in place for an update to something a page already covers (the same
service, the same incident's postmortem); create a new page only for a genuinely distinct entity. Check
`index.md` and `search_knowledge` first — a near-miss title is a strong signal an existing page already
owns this entity.
