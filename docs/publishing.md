# Publishing

How a Post gets from a draft to something live on a platform — and how it works when there is no
platform integration at all.

The pipeline is defined by a **lifecycle Workflow**, not by anything hardcoded. `MARKETING` is the
built-in one (`schema/examples/marketing.workflow.json`): noun `Post`, `default_view: calendar`,
`asset_types` naming the four publishable platforms. Any Workflow declaring an asset type named for a
publishable platform gets the whole pipeline; a Workflow that does not (`ENGINEERING`, which declares
`github_pr`) never sees it.

## The shape

```
DRAFT ──► IN_REVIEW ──► APPROVED ──► SCHEDULED ──► PUBLISHED
              ▲   │         │            │    └──► FAILED ──┐
              └───┘         │            │                  │
        CHANGES_REQUESTED   └────────────┘◄─────────────────┘
                              (unschedule / retry)
```

`IN_REVIEW → APPROVED` is the review-gated edge. Every publishing rule is enforced there **and on every
edge into the scheduled status** (`APPROVED → SCHEDULED`, `FAILED → SCHEDULED`): a Post approved with
time to spare and scheduled once its fire time had crept inside the floor used to enter Scheduled
unvalidated and then sit forever, because the native hand-off refused it. Edges *out of* Scheduled
("Unschedule") are never gated, so a human can always pull a post back. Passing a gate edge requires:

- at least one **publish target** selected,
- at least one uploaded media file,
- a fire time far enough out for every selected destination, with an IANA timezone — the floor is the
  longest lead any of them needs (Facebook's native scheduler wants **10 minutes**, an app-managed
  Instagram or TikTok destination one minute, YouTube and a manual destination only "in the future"),
  and ten minutes while no destination is selected yet,
- media on **every** target, which each selected platform will actually accept and can publish as one
  post (`MediaTargetValidator`),
- for an API TikTok target: a privacy level, and the creator's recorded consent.

**Ask before you move.** `GET …/work-items/{id}/publish-preflight` runs the same validators without
transitioning and returns every blocker and warning with a stable `code`, the next gate move, whether a
review currently satisfies the gate, whether the creator's consent stands, and `earliestFireTime` — "as
soon as possible" for the current destinations, which a client sets as `scheduledFor` rather than
guessing a lead. The 422 a refused transition throws is the same list, so what a client shows beforehand
and what the transition says cannot disagree.

### Which status is "scheduled"

A publishing Workflow names the status its Posts wait in with `publishes_from` (MARKETING:
`"publishes_from": "SCHEDULED"`). The pollers dispatch from it, the validators guard every entry into it,
and everything from it onward is frozen. A snapshot pinned before the field existed falls back to a status
literally named `SCHEDULED`, so existing Posts keep dispatching. Publishing a definition whose
`asset_types` name a platform but that has neither is refused.

### A lifecycle with no review gate

`schema/examples/marketing-autopilot.workflow.json` is MARKETING with the review removed:
`DRAFT → SCHEDULED → PUBLISHED | FAILED`, `FAILED → SCHEDULED` to retry, `SCHEDULED → DRAFT` to pull a post
back. Because the schedule edge is a gate, it is validated exactly as MARKETING's approval is; because the
scheduled region freezes content, a scheduled Post refuses edits and names "unscheduled" as the way out
rather than a reviewer's send-back. Import it with `create_workflow` for a project whose agent is trusted
to publish without a human in the loop. It is not seeded by default.

## Content freezes when it goes for review

Everything a review is about — the caption, the media, the schedule, the destinations — is frozen from
the moment the item enters its review status, and stays frozen through Approved, Scheduled and
Published. The only way to change any of it is for a reviewer to send it back; from Changes Requested
the author has the pen again.

This is not merely tidiness. It was briefly the other way, and the gap was real: an author could rewrite
the caption while a reviewer was reading, and the approval that reviewer then gave — for what they had
read — attached to something else. Freezing at submit means an approval always describes the thing that
was approved, and "send it back" is the reviewer's decision rather than the author's.

The rule is derived from the Workflow, not hardcoded: the freeze covers the review status and everything
reachable past the gate, so Draft and Changes Requested stay editable and a Workflow with no review gate
freezes nothing.

Everything after approval is bound to what was approved. `reviews.bundle_hash` is a SHA-256 over the
caption, fire time, targets and uploaded assets; changing any of them reverts the Post to review and
revokes anything already handed to a platform. That is why editing a schedule after approval sends the
Post back — it is working, not misbehaving.

## App credentials

Meta, TikTok and YouTube publish as a **platform app**, and that app belongs to the workspace, not to
whoever runs the deployment. Each carries its own App Review or audit, its own rate limits, and its own
relationship with the creator whose account it posts to, so there is no deployment-wide app to inherit
and no environment variable to set.

A project admin enters the pair at **Settings → Integrations → *connector***: the client id (public,
shown back in full) and the client secret (stored under the same KMS envelope as every other
Integrations secret, and never returned — only its last four characters are). **Verify** probes the
provider with them and reports what it proved. Until a pair is stored the connector is offered but not
connectable, and its card says so rather than sending anyone into a consent flow that would fail.

The credential is keyed on the connector, so YouTube's Google OAuth client is separate from the one
GSC and GCP Billing inherit from the deployment — opting YouTube out of that shared client took nothing
away from them.

Two consequences worth knowing:

- **Consent, exchange and completion all run as the same app.** The credentials resolved when the
  consent URL is built are carried through the token exchange and into the connector's completion hook,
  which is what Meta's long-lived token swap authenticates with. Nothing re-reads them halfway.
- **Clearing a credential takes the connector offline for new connections.** Connections that already
  exist keep working on their stored tokens until a refresh needs the app again.

## Publish targets and lanes

A **publish target** is one row per (Post, platform, account): the durable anchor the pipeline hangs
off, carrying the globally unique idempotency key that makes publishing at-most-once. A single Meta
connection yields two targets (a Facebook one and an Instagram one), which is why uniqueness is on the
(work item, platform, connection) triple.

Each target has a **lane**, which decides who does the posting:

| Lane | Who publishes | Platforms | At fire time |
|---|---|---|---|
| `NATIVE` | The platform's own scheduler | Facebook, YouTube | Conductor hands the post and its time over ahead of the window and stops being involved |
| `APP_MANAGED` | Conductor | Instagram, TikTok | Conductor holds the post and calls the platform when it comes due |
| `MANUAL` | A person | all four | Conductor calls nothing; the target is flagged for a human |

Automated targets are derived from the project's ACTIVE connections — connect an account and it
appears, disconnect it and it stops being offered. A manual target is derived from nothing and is
always offered, one per platform.

## The manual lane

A manual destination is how a Post publishes with **no integration at all** — a project still waiting
on platform App Review, one posting through a personal account, or anything on a surface (a Story,
say) that no API reaches.

It is not a way around the pipeline. A manual Post goes through the same review gate, the same media
rules, the same schedule and the same calendar. The only thing a human takes over is the posting.

1. **Select it.** In *Publishing to* on the Post, pick e.g. "TikTok (manual)". Over MCP:
   `set_publish_targets` with `{platform: "tiktok"}` and no `connectionId`.
2. **Approve and schedule** as normal.
3. **At fire time** the target moves `PENDING → AWAITING_MANUAL`. *Publishing results* shows it as
   **Post it now** and asks for the link.
4. **Post it yourself**, then record the live URL — the form on the Post, or
   `complete_manual_publish` over MCP. The URL is required: with no platform to ask, it is the only
   record the destination ever went out.

The target then moves to `PUBLISHED` exactly as an API one does: same destination Asset recorded, same
roll-up of the Post, same link on the calendar and the list. A manual publish is not a second class of
result.

Two rules worth knowing:

- **An automated target cannot be marked published by hand.** It has a poller that will publish it and
  report the real outcome; declaring it published would strand a post still queued to go out. Drop it
  with `set_publish_targets` instead.
- **TikTok's privacy-level and consent gates do not apply to a manual target**, and only to a manual
  one. Both exist because Conductor would be posting on the creator's behalf; on this lane the creator
  is in TikTok's own composer, seeing TikTok's own preview. An `APP_MANAGED` TikTok target alongside a
  manual one still trips both.

`AWAITING_MANUAL` counts as in-flight, so a Post waiting on a person is not rolled up as failed, and
unscheduling stands a flagged target back down to `PENDING`.

> The publishing pollers are off on the `local` profile
> (`conductor.post-publish.enabled=false`), so nothing fires — including the manual flagging pass.
> Run with `-Dconductor.post-publish.enabled=true` to exercise them on a laptop; the `local` profile's
> connectors are stubs, so nothing reaches a real platform.

## Per-target caption and media

A Post has one caption and one set of uploaded media, and by default every destination publishes all of
it. That default is a real behaviour, not just an initial value: an **inheriting** destination keeps
following the Post as files are added and removed.

Any destination can be given its own instead. On the Post, *Publishing to* → **Customize for this
destination** opens a caption box and an ordered picker over the Post's own files; over MCP the same
thing is `captionOverride` and `assetIds` on `set_publish_targets`. Both are optional, and leaving them
out is what inherits.

Two rules follow from that, and they are the ones people trip over:

- **Order is content.** Instagram crops every carousel item to the first item's aspect ratio and TikTok
  takes a photo post's cover from the first image, so re-ordering a selection changes the post — and,
  like any other bundle edit, sends an approved Post back for review.
- **Selecting is set-replace, everywhere.** `set_publish_targets` sends the complete selection, so a
  target re-sent without `captionOverride` or `assetIds` has them cleared. The UI re-sends every
  destination's content on every save for exactly this reason.

A destination that chose files which were later deleted is **not** the same as one that never chose:
it has no media, and the approval gate says so rather than quietly publishing the whole Post there.
Deleting media is only possible before review, so this can never rewrite an approved bundle.

## What each platform accepts

Checked at the approval gate, per target, against that target's own media — so a PNG uploaded for
Facebook no longer blocks the Post because Instagram is also selected.

| Platform | Media | Copy |
|---|---|---|
| **Instagram** | 1 item, or a carousel of 2–10 (images and video may mix). Feed images must be JPEG, aspect 4:5 to 1.91:1 | caption ≤ 2200 characters |
| **Facebook** | 1 video, **or** any number of photos; never both. Video ≤ 1.5 GB | message effectively uncapped |
| **TikTok** | 1 video (≤ 4 GB, within the creator's own duration cap), **or** 1–35 images. Never both, and photo-post images must be **JPEG or WEBP** — PNG is refused here and nowhere else | video caption ≤ 2200; photo post: title ≤ 90 (cut, with a warning), description ≤ 4000 |
| **YouTube** | exactly 1 video, no images | title ≤ 100 characters; description ≤ 5000 **bytes** of UTF-8 |

A carousel of mixed aspect ratios is a warning, not a refusal: Instagram will publish it, cropping every
item to the first one's shape, which may well be what was wanted.

> **TikTok photo posts need one piece of setup.** TikTok has no chunked upload for images, so a photo
> post hands over URLs for TikTok to fetch — which requires the storage host to be registered as a
> verified URL prefix for the app in the TikTok developer portal. Without it every photo post fails with
> a message naming this; video posts are unaffected, since they upload their bytes.

## Outcomes

A Post's single status cannot describe a partial send, so every target keeps its own row. A Post that
reached Instagram and was refused by YouTube is *published to one account and needing attention on
another*, and both halves stay on screen. Retrying re-fires only the `FAILED` rows, with a fresh
idempotency key.

Each success records a typed **link Asset** on the Post whose `ref` is the permalink. That is what
puts the live link on the calendar chip and the list row (`externalLinks` on the Work Item response) —
deliberately generic, so an Issue's `github_pr` gets the same treatment.

## Getting told about it

Publishing rides the same notification system everything else does, with its own channel so a marketing
Discord channel is not the engineering one.

Configure it at **Settings → Notifications → Publishing**: a webhook URL and which events it carries.
Two things arrive there:

- **A Post's status changing** — the same Workflow-agnostic `WORK_ITEM_STATUS_CHANGED` an Issue fires,
  including the Published/Failed roll-up once every destination has landed.
- **A destination coming due that publishes by hand** (`POST_AWAITING_MANUAL`) — the one publishing event
  that is not a status change, because the Post itself stays Scheduled while a destination waits on a
  person. Without it the manual lane would depend on someone happening to open the Post.

Routing is decided from the event, not from a channel name: an item on a Workflow that declares
publishable `asset_types` prefers the Publishing channel and **falls back to the Issues channel when a
project has not configured one**, so adding this took nothing away from a project that already had
notifications. A manual-publish alert has no fallback — there is nothing sensible to say about it in an
Issues channel — so it is simply silent until a Publishing channel exists.

## Adding a platform

Every platform the pipeline can target is one `PublishPlatform` value in
`service/publish/PublishPlatformRegistry`: its id (the `post_publish_target.platform` vocabulary), label,
connector, asset type, manual label, lane, publish/revoke/confirm action ids, the `publishOptions` keys it
accepts, its minimum and maximum lead times, and which extra approval rules it trips. The validators, both
dispatch pollers, the confirmation poller, the outcome service and the target picker all read from it, and
`GET /publish-targets` reports each option's `optionKeys` from it so a client never guesses.

To add one: append a `PublishPlatform` to the registry, add its action ids to the connector's tool spec,
add its asset type to `marketing.workflow.json` and its id to the OpenAPI `platform` enums, then give
`MediaTargetValidator` its media rules. `PublishPlatformRegistryContractTest` fails until the spec, the
example Workflow and the enums agree with the registry.

## Where things live

- `service/publish/PublishPlatformRegistry` — the one table of platforms; everything below reads it
- `workflow/PostPublishScheduler` — the APP_MANAGED dispatch poller and the MANUAL flagging pass
- `service/NativeHandoffService` — hand-off, confirmation and revocation for the NATIVE lane
- `service/PublishOutcomeService` — outcome recording, the Post-level roll-up, retry, manual completion
- `service/PostScheduleValidator`, `MediaTargetValidator`, `PublishOptionsValidator` — the approval gate
- `service/PublishBundleHasher`, `PublishBundleGuard` — binding an approval to what was approved
- `service/PublishTargetMediaResolver` — the one answer to "what does this target actually publish?"
- `service/PublishInputBuilder` — the payload both dispatchers hand a platform action

See [`workflows.md`](workflows.md) for the Workflow YAML and statechart format, and
[`integrations-adding-a-connector.md`](integrations-adding-a-connector.md) for connecting an account.
