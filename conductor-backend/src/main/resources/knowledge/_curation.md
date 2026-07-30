---
type: schema
title: Curation policy
description: What does NOT belong in this wiki. Read alongside _schema.md before filing any batch.
---

# Curation policy

`_schema.md` tells you *how* to write a page. This page tells you *whether to write one at all*.

Filing something worthless is not a neutral act. It costs every future reader's attention, it dilutes
search, and it turns a reference into a log. **Skipping a source is a successful outcome** — report it
in `write_knowledge_pages`'s `skipped` with a one-line reason and move on. A batch where you file 2 of
10 sources and skip 8 is a good batch.

## The bar

File a source only if a teammate joining this project in six months would be worse off without it.

A source clears the bar when it carries at least one of:

- A **durable fact** — how something works, why it was chosen, who owns it, what a number is.
- A **decision and its reasoning**, including decisions that were later reversed.
- **New information about something already documented here** — which means editing that page, not
  creating a second one.
- A **pattern across several sources** worth stating once, in one place.

## What to skip

- **Process noise with no outcome.** A work item created and closed with nothing done. A status
  bounce. A reassignment. A retitle. A ticket that only ever moved between columns.
- **Already fully captured.** An existing page says it, and you have nothing to add. Do not restate a
  page to prove you read the source.
- **A bare event with no content.** A webhook body of ids. A notification. An empty, truncated, or
  unrecoverable payload.
- **Ephemeral or already superseded.** A build result. An alert that resolved. A draft replaced hours
  later by the thing you should file instead.
- **One unattributed opinion** with nothing behind it. An aside is not a finding.
- **Anything matching a rule below**, or in the relevant domain's `<domain>/_curation.md`.

When torn between a thin page and a skip: **skip**. A missing page is self-correcting — the next
source on the subject creates it. A page of noise has to be found and deleted by a human.

## Never file

- Secrets, credentials, tokens, or personal data not already public within this project.
- A verbatim dump of a source payload. A page is a synthesis; nothing to synthesize means skip.

## Do-not-file rules

Rules here are binding. Read each one as describing a **class** of sources, not just the single page
it came from — a human wrote it because the wiki was already worse for having that page.

<!-- Rules are appended below by the "Not worth filing" action on a page, newest last. Edit,
     generalize, or delete any of them freely — this is a human-owned page. -->
