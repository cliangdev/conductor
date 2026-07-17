---
type: schema
title: People domain schema
description: Page-type taxonomy, path layout, and body templates for people knowledge — team members and meetings.
---

# People domain schema

This page is the librarian's style guide for everything filed under `people/`. Read it before writing
any page in this domain — it extends the root [Knowledge Center schema](/_schema.md) (frontmatter
contract, linking, create-vs-edit) with people-specific types and templates.

## Page-type taxonomy

| `type` | For |
|---|---|
| `person` | A team member or notable external contact. |
| `meeting` | Notes/outcomes from a specific meeting. |

If a source doesn't clearly fit one of these, prefer the closest match over inventing a new type; raise
the gap in your run summary instead.

## Path layout

- `people/` — `person` pages.
- `people/meetings/` — `meeting` pages.

Use lowercase, hyphenated filenames (e.g. `people/jane-doe.md`).

## Body templates

Every page's body follows its type's template below. Keep every section; write "None yet." rather than
dropping one.

### `person`

`## Role` · `## Working on` · `## Notes`.

### `meeting`

`## Attendees` · `## Outcomes` · `## Action items`.

## Create vs. edit

Same rule as the root schema: edit in place for an update to something a page already covers (the same
person, a follow-up on the same meeting series); create a new page only for a genuinely distinct entity.
Check `index.md` and `search_knowledge` first — a near-miss title is a strong signal an existing page
already owns this entity.
