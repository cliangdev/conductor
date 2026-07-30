---
type: schema
title: Knowledge Center schema
description: Frontmatter contract, path layout, and linking conventions for this project's wiki.
---

# Knowledge Center schema

This page is the librarian's style guide. Read it before writing any page.

> **Before deciding how to write a page, decide whether to write one.** Read
> [`/_curation.md`](/_curation.md) — the project's curation policy — and the relevant domain's
> `<domain>/_curation.md`. Not filing a source is a normal, correct outcome; report it in
> `write_knowledge_pages`'s `skipped` with a one-line reason.

## Frontmatter contract

Every page is Markdown with a leading YAML frontmatter block (`---` ... `---`).

- `type` (**required**) — one string identifying the page's kind. A page with no `type` is invalid and
  will be rejected. Root-level types are `schema` and `decision` (see below); every other type is owned
  by a [domain](#domains) — read that domain's schema page for its taxonomy before picking one.
- `title` (recommended) — short human-readable name, used in `index.md` and search results.
- `description` (recommended) — one sentence, used in `index.md` and search results.
- `resource` (recommended) — a stable external identifier this page tracks, if any (e.g. a Work Item
  key, a GitHub repo, a person's email) — lets the librarian find "the page for X" without a full-text
  search.
- `tags` (recommended) — a short list of free-form labels for cross-cutting grouping (e.g.
  `[billing, q3-roadmap]`).
- `timestamp` (recommended) — ISO-8601 timestamp of the fact this page (or its latest edit) reflects,
  distinct from the page's own revision history (which tracks *when the wiki was edited*, not when the
  underlying fact occurred).
- `confidence` (optional) — one of `high` / `medium` / `low`, your honest assessment of how well-
  supported this page's content is by its sources. Use `low` for a single ambiguous mention; `high` for
  something stated plainly and repeatedly, or confirmed by an authoritative source (e.g. code itself).
- `sources` (optional) — list of knowledge-inbox source ids or external refs this page's content was
  derived from, for provenance. `write_knowledge_pages`'s `sourceIds` parameter already links a
  revision to its sources structurally (visible via list-revisions) — use this frontmatter field only
  when you want the linkage visible directly on the rendered page itself.
- Any other key round-trips verbatim — the parser preserves fields it doesn't know about, so it's safe
  to add page-type-specific fields (e.g. a `decision` page might add `status: accepted`).

## Domains

Every top-level directory except `decisions/` is a **domain**, with its own `<dir>/_schema.md` page
defining that domain's page-type taxonomy, path layout, and body templates. **Read the relevant domain
schema before filing a page there** — this root page only covers what's cross-cutting.

| Domain | Purpose |
|---|---|
| [`engineering/`](/engineering/_schema.md) | Architecture, runbooks, postmortems, engineering decisions, integrations. |
| [`product/`](/product/_schema.md) | Features, experiments, feedback synthesis. |
| [`marketing/`](/marketing/_schema.md) | Campaigns, personas, positioning, competitors. |
| [`finance/`](/finance/_schema.md) | Financial metrics and spend decisions. |
| [`people/`](/people/_schema.md) | Team members and meetings. |

This table reflects the seeded defaults at the time this page was written — it does not update itself as
domains are added or changed. The domain registry is the authoritative, current list: use the
`list_knowledge_domains` tool, or the Domains panel on the Knowledge index page, to see it.

Never invent a new top-level directory for a single page — file it under the closest existing domain
above, or `decisions/` as a fallback.

## `decisions/` — cross-cutting decisions

`decisions/` stays outside the domain system: a decision can span or precede any domain, so it isn't
owned by one. File a `decision` page here for anything that doesn't clearly belong to one domain's own
decision log (e.g. `engineering/decisions/` for architecture/tech-stack ADRs, `finance/decisions/` for
spend calls) — those domain-scoped decision types exist for exactly that narrower case.

### `decision` (ADR-style)

`## Context` · `## Decision` · `## Alternatives considered` · `## Consequences`. Add
`status: proposed|accepted|superseded` to frontmatter.

## Create vs. edit

- **Edit in place** when a source is an update, correction, or additional detail about something a
  page already covers — the same entity you'd link to from elsewhere (same person, same feature, same
  decision). Bump content, don't fork a near-duplicate page.
- **Create a new page** when the source describes a distinct entity — one you would link to *from*
  other pages, not just mention inline. If in doubt, check `index.md` and `search_knowledge` first; a
  near-miss title is a strong signal an existing page already owns this entity.

## Linking

Link to other pages with bundle-absolute Markdown links: `[Knowledge Center](/product/features/knowledge-center.md)`.
Relative links (`../foo.md`) also resolve, but bundle-absolute is preferred for clarity across
directories. A link to a page that doesn't exist yet is fine — it resolves once that page is created.
