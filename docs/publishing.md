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

`IN_REVIEW → APPROVED` is the only review-gated edge, and it is where every publishing rule is
enforced. Passing it requires:

- at least one **publish target** selected,
- at least one uploaded media file,
- a fire time at least **10 minutes** out, with an IANA timezone,
- media that each selected platform will actually accept (`MediaTargetValidator`),
- for an API TikTok target: a privacy level, and the creator's recorded consent.

Everything after approval is bound to what was approved. `reviews.bundle_hash` is a SHA-256 over the
caption, fire time, targets and uploaded assets; changing any of them reverts the Post to review and
revokes anything already handed to a platform. That is why editing a schedule after approval sends the
Post back — it is working, not misbehaving.

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

## Outcomes

A Post's single status cannot describe a partial send, so every target keeps its own row. A Post that
reached Instagram and was refused by YouTube is *published to one account and needing attention on
another*, and both halves stay on screen. Retrying re-fires only the `FAILED` rows, with a fresh
idempotency key.

Each success records a typed **link Asset** on the Post whose `ref` is the permalink. That is what
puts the live link on the calendar chip and the list row (`externalLinks` on the Work Item response) —
deliberately generic, so an Issue's `github_pr` gets the same treatment.

## Where things live

- `workflow/PostPublishScheduler` — the APP_MANAGED dispatch poller and the MANUAL flagging pass
- `service/NativeHandoffService` — hand-off, confirmation and revocation for the NATIVE lane
- `service/PublishOutcomeService` — outcome recording, the Post-level roll-up, retry, manual completion
- `service/PostScheduleValidator`, `MediaTargetValidator`, `PublishOptionsValidator` — the approval gate
- `service/PublishBundleHasher`, `PublishBundleGuard` — binding an approval to what was approved

See [`workflows.md`](workflows.md) for the Workflow YAML and statechart format, and
[`integrations-adding-a-connector.md`](integrations-adding-a-connector.md) for connecting an account.
