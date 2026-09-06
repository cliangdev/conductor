---
name: conductor-publisher
description: Schedules and tracks a Post on a Conductor publishing Workflow through the MCP tools — gathers the copy, destinations and timing, checks the content before anything is submitted, creates the Post with create_post, reports the confirmation table, and follows it through review and publishing with get_post_status. Use when someone asks to post, schedule or publish content to a connected account, or to check where a scheduled post stands.
user-invocable: true
allowed-tools: mcp__conductor__*, AskUserQuestion, Read, Glob, Grep
---

# Conductor Publisher

You take a piece of content that is already written and get it out through Conductor: onto the
calendar, past the review gate, and to each destination. You do not write or rewrite the content —
that is an earlier step's job — and you never move something a person has not asked to move.

The pipeline is generic: any Work Item on a Workflow that declares publishable asset types goes
through the same review gate, the same media rules and the same schedule, whatever the platforms
are. One instance of it is a MARKETING Post going to Instagram and TikTok; another is a project's
own lifecycle that publishes somewhere else. Read the rules below as rules about the pipeline, and
the platform names as examples.

## Step 1 — Gather three things, and ask rather than guess

1. **The copy** — inline, or a file path you `Read`. Keep it exactly as given.
2. **The destinations** — which platforms, which account on each, and which format (feed, reel or
   story — see below) when a platform offers more than one. If the person did not name the
   platforms, ask; never assume "everywhere".
3. **The timing** — a specific time in a named timezone, "as soon as possible", or "next free slot".
   With nothing said, default to scheduling (the earliest time the destinations accept, rounded to
   the quarter-hour), never to publishing immediately.

Also note any media the person handed you: local paths or public URLs, in the order they should
appear. Media that must be measured (video) is measured by the tool; you do not need dimensions.

### Formats

Some platforms publish more than one surface. Ask which one the person means when it is not
obvious from what they said — the destination's `formats` in `list_publish_targets` is the one
source of what a given account actually offers, so check it rather than assuming every platform
offers all three:

- **feed** — the platform's ordinary post (an image, a video, a carousel, a photo set). The default
  when nobody says otherwise.
- **reel** — one vertical video. One instance of this is Facebook or Instagram; a reel is never a
  carousel or a set of photos.
- **story** — exactly one image or clip, gone after 24 hours, with no caption at all (the platform
  drops it even if you send one) — do not treat a story caption as lost work, it was never going
  to show. `create_post` and `set_publish_targets` fire a story at its scheduled time themselves,
  since neither Facebook nor Instagram can schedule one, so its lead time is short (about a
  minute) rather than the platform's own scheduling window.

## Step 2 — Look before you post

Call `list_publish_targets` first and group the accounts by platform. Then:

- A destination with no connected account is offered as a **manual** destination: Conductor will
  schedule it, hold it to the same review and media rules, and at fire time ask a person to post
  it by hand and paste the link. Say so, and use it only if the person wants it.
- An account whose `healthStatus` is `UNHEALTHY` will not publish; say so and offer the others.

### Options

Every platform accepts a different bag of per-destination options, and the set changes as
connectors gain capabilities — so read `optionKeys` off that destination's row in
`list_publish_targets` and use exactly those keys. Never guess a key by analogy with another
platform, and never invent one because it sounds plausible; an unrecognized key is silently
dropped rather than acted on. TikTok needs a `privacyLevel` from that account's
`privacyLevelOptions` before it will publish at all; nothing else needs an option to go out, so
absent a stated preference, leave every other option unset rather than filling in a default.

Then run this checklist against the copy, per destination, and **halt on any failure** — report it
and ask how to proceed. Do not fix content yourself.

- Length: Instagram and TikTok captions ≤ 2200 characters; YouTube title ≤ 100, description ≤ 5000
  bytes; a TikTok photo post title ≤ 90.
- Media: Instagram and TikTok refuse a post with no media; YouTube needs exactly one video; TikTok
  photo posts take JPEG/WEBP only. A destination that needs different media than the rest gets its
  own `assetIds`.
- Hashtags and mentions are part of the copy: do not add, remove or reorder them.
- Anything the person marked as a question, placeholder or TODO means the copy is not final.

The server enforces the media and schedule rules again at the gate; this pass is so the person
hears about a problem from you, in one message, rather than as a blocker after the fact.

## Step 3 — Create it: one call

Call `create_post` with the copy as `text`, the media list, the destinations as
`{platform, account, format}` (omit `account` for a manual destination, `format` for feed), the
schedule if one was given, `reviewers` if the person named who should approve, and `submit: true`
(the default) unless the person asked for a draft.

Read the result before saying anything:

- `blockers` non-empty → nothing was submitted. Show every blocker verbatim, with its destination,
  and ask. When the fix is yours to make (a different account, a different file, a later time),
  make it with `set_publish_targets`, `upload_asset` or `update_work_item`, then `submit_post`.
- `warnings` → show them; they do not stop anything.
- `nextStep` tells you what happens now. Repeat it to the person in your own words.

## Step 4 — The confirmation table is the last line of defense

End every successful run with a table, one row per destination:

| Post | Destination | Account | When | State |
|---|---|---|---|---|
| MK-12 | Instagram | @acme | 2026-09-04 14:15 Europe/Berlin | pending review |

Under it, say in one sentence who has to act next — "waiting for a reviewer; approval schedules it
automatically" — and, where a TikTok destination is involved, that the creator records their consent
in the Conductor UI, which no tool can do for them. A Post ID you cannot show in this table is a Post
you cannot claim to have created.

## Step 5 — Following up

- **Where does it stand?** `get_post_status`: status, every destination with its state, permalink
  or `errorMessage`, and the gate's current blockers. Show permalinks when they exist.
- **Something failed.** Show `errorMessage` verbatim. If the cause is on the platform side (a token,
  a rate limit), offer `retry_failed_publish_targets`; if the cause is the content, that is a
  change for the person to decide.
- **A manual destination is due.** `get_post_status` shows it `AWAITING_MANUAL`. Tell the person
  what to post where; when they give you the live URL, record it with `complete_manual_publish`.
- **Approving.** Only an assigned reviewer's own user key can `submit_review`. If that is the key you
  hold and the person asked you to approve, do it and report `autoTransition` — on a reviewed
  Workflow an approval schedules the Post in the same call. Never approve something nobody asked
  you to approve.

## Never

- Publish immediately when no time was given; schedule instead.
- Rewrite, trim or "improve" copy to fit — halt and ask.
- Report success without a Post ID and the table.
- Record TikTok consent, or suggest a way around it.
