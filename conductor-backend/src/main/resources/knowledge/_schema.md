---
type: schema
title: Knowledge Center schema
description: Frontmatter contract, page-type taxonomy, path layout, and linking conventions for this project's wiki.
---

# Knowledge Center schema

This page is the librarian's style guide. Read it before writing any page.

## Frontmatter contract

Every page is Markdown with a leading YAML frontmatter block (`---` ... `---`).

- `type` (**required**) — one string from the [page-type taxonomy](#page-type-taxonomy) below. A page
  with no `type` is invalid and will be rejected.
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

## Page-type taxonomy

| `type` | For |
|---|---|
| `person` | A team member or notable external contact. |
| `project` | A Conductor project/workspace-level summary. |
| `decision` | A recorded decision — what was decided, why, alternatives considered. |
| `meeting` | Notes/outcomes from a specific meeting. |
| `metric` | A tracked metric's definition, current value/trend, and where it comes from. |
| `feature` | A user-facing product feature. |
| `architecture` | A system/service/module's design. |
| `integration` | A third-party integration or connector's setup/behavior. |

If a source doesn't clearly fit one of these, prefer the closest match over inventing a new type;
raise the gap in your run summary instead.

## Path layout

- `people/` — `person` pages.
- `decisions/` — `decision` pages.
- `product/features/` — `feature` pages.
- `engineering/architecture/` — `architecture` pages.
- `finance/` — budget/spend/revenue-adjacent `metric`/`decision` pages.
- `marketing/` — campaign/positioning-adjacent pages.
- Meetings and cross-cutting metrics that don't fit a specific area: file under the most relevant
  existing directory above, or `decisions/`/`people/` as a fallback — don't invent a new top-level
  directory for a single page.

Use lowercase, hyphenated filenames (e.g. `product/features/knowledge-center.md`).

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
