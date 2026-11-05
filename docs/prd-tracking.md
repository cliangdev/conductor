# PRD → Delivery Tracking

How a PRD becomes trackable work: child Work Items, dependencies, acceptance criteria, milestones, appetite,
and the signals the server derives from them. The web views (Plan tab, overview, roadmap) and the
`/conductor:breakdown` → `/conductor:implement` flow build on the model described here.

## Principles

- **Agents do the bookkeeping, humans steer.** Blocked, ready, stale, needs-input, progress and health are
  *derived* on read from facts agents already record (status transitions, criteria, sessions). Nobody types
  a percentage.
- **No estimates.** A PRD carries an *appetite* (a time box such as `2 weeks`). Dates come from a throughput
  forecast computed from completed tasks (Tranche 3), not from sizing.
- **Shallow dependencies.** `BLOCKS` and `DISCOVERED_FROM` only. The useful output is the **ready queue**:
  what can be picked up right now.
- **Health, not percent.** A PRD's health (`ON_TRACK` / `AT_RISK` / `OFF_TRACK` + note) is drafted by an agent
  and confirmed by the owner (Tranche 3).

## Model

```
PRD (ENGINEERING, type PRD)
└── Task  (TASK workflow)            ← an "epic" is just a Task with children
    ├── Task
    └── Task
```

- **Hierarchy** is `work_items.parent_work_item_id`, recursive, depth ≤ 5. Children get ordinary display
  IDs (`COND-143`) from the same per-project sequence; the UI shows the parent beside them. A child created
  without a `workflow` defaults to the `TASK` workflow and type and inherits its parent's milestone.
- **`TASK` lifecycle** — a built-in, sidebar-hidden Workflow seeded for every project:
  `TODO` (open, initial) → `IN_PROGRESS` → `DONE` (terminal); `CANCELLED` (terminal); `IN_PROGRESS → TODO`
  (Stop), `DONE → TODO` (Reopen). The system trigger `children_done` moves a Task to `DONE` when all of its
  direct children are terminal **and at least one of them finished in a status other than `CANCELLED` or
  `CLOSED`** (matched on status id, the cancel-style ids the built-in Workflows use). A Task whose children
  were all cancelled stays where it is, for a person to finish or cancel. Completion rolls **up only**:
  reopening a child never reopens its parent, and a PRD (on ENGINEERING, which has no `children_done` edge)
  is completed by its PR merge as before.
- **System-only edges** — the `children_done` edges (`TODO → DONE`, `IN_PROGRESS → DONE`) are the system's,
  not a person's: `available-transitions` does not list them and a `PATCH` of `status` through one is refused
  like any other invalid transition (400). A person finishes a Task with the manual `IN_PROGRESS → DONE`
  (Finish). The rule is a trigger flag, not "has a trigger": an edge that also carries a Review gate
  (ENGINEERING's `CODE_REVIEW → DONE`, trigger `pr_merged`) stays a person's move once approved, and
  MARKETING's `APPROVED → SCHEDULED` stays a click even though `review_approved` also fires it.
- **Dependencies** — `work_item_dependencies(work_item_id, related_work_item_id, kind)`; a row reads
  "*work_item* **kind** *related*": `A BLOCKS B`, `C DISCOVERED_FROM D`. Same project on both ends, no
  self-links, no `BLOCKS` cycles (checked transitively on write, 409), at most 25 outgoing and 25 incoming
  `BLOCKS` per item. The inverse (`blockedBy`, `discovered`) is a read projection, not a second row.
- **Acceptance criteria** — `work_item_criteria`: ordered rows on a Task with `text`, `kind` (`auto` =
  test-verified and the default, `manual` = a person checks), `met`, `evidence`, and an optional
  `externalRef` linking back to the PRD's `AC-P0-1.1` IDs. `met=true` stamps who and when; `met=false`
  clears both. `PUT …/criteria` is replace-all, but a criterion sent again (matched by `externalRef`, or by
  identical text when it has none) keeps its met state and evidence, so re-asserting a list never un-ticks it.
- **Milestones** — `milestones(project_id, name, target_on, state)`; the only dated object. An item belongs to
  at most one; children inherit the nearest ancestor's milestone for progress rollups.
- **Priority** — `P0`–`P3` or none; distinct from a PRD's internal P0/P1 feature tiers.
- **Appetite** — `appetite` (normalised label) + `appetite_days`; accepted forms `2 weeks`, `2w`, `10 days`,
  `10d`, `1 month` (week = 7, month = 30). An empty string clears it, like `dueOn`.
- **Cycle timestamps** — `started_at` is stamped on the first move into an `in_progress`-category status,
  `completed_at` on entering a terminal status (cleared on reopen). These feed the forecast.
- **Agent sessions** — `work_item_agent_sessions(session_key, agent_label, mode, activity, note,
  last_heartbeat_at, ended_at)`: a liveness record per agent run on an item. `mode` is `DELEGATE` (a human
  stays assignee; the agent acts for them) or `ASSIGNEE`. `activity` is `WORKING`, `NEEDS_INPUT` (with the
  question in `note`), `DONE` or `ERRORED`. Upserts by `(work_item_id, session_key)` are idempotent, so a
  skill can report freely: `DONE` and `ERRORED` end the session, and a later `WORKING` or `NEEDS_INPUT` on
  the same key reopens it. This is separate from `work_item_step_runs`, which records what a transition's Step
  produced for a Review gate.

## Derived signals

Computed on read by `WorkItemSignalService`, batched per page, and returned as `signals` on every Work Item:

| Signal | Rule |
|---|---|
| `blocked` | at least one `BLOCKS` blocker whose status is not terminal in its own Workflow (a cancelled or closed blocker unblocks); `blockedBy` lists only those unfinished blockers |
| `ready` | not blocked, status category `open`, not terminal — assignee is irrelevant |
| `stale` | status category `in_progress`, `updated_at` older than 7 days, and no agent session `WORKING` with a heartbeat in the last 7 days |
| `needsInput` | the latest open agent session on the item (or, for a PRD, on any descendant) is `NEEDS_INPUT` |

`progress` on an item is `{total, done, percent, criteriaTotal, criteriaMet}` over the *leaves* of its
subtree (items with no children), where done = terminal in the leaf's own Workflow. A tree node's own
`criteriaTotal`/`criteriaMet` count just that node's criteria. Signals cost three batched queries per page
plus one subtree walk per item that has children (for the descendant `needsInput` check), so a PRD's single
GET is one query dearer than a leaf's.

## Listing

`GET /api/v2/projects/{id}/work-items` returns **top-level items by default** (`parentId=none`), each with
`childCount`. `parentId=any` includes children, `parentId=<id>` lists direct children, `rootId=<id>` a whole
subtree. Filters: `milestoneId`, `priority`, `assigneeId`, `ready`, `blocked`, `stale`; `withProgress=true`
adds the rollup; `sort` is `updated | created | priority | dueOn | sequence`. The MCP `list_work_items` tool
defaults the same way and returns compact rows, so an agent sees PRDs, not three hundred tasks.

## API and MCP surface

| Need | REST (`/api/v2/projects/{id}`) | MCP |
|---|---|---|
| Create a task under a PRD | `POST /work-items` with `parentWorkItemId`, `priority`, `criteria[]`, `blockedBy[]` | `create_work_item` |
| Change priority / milestone / appetite / parent | `PATCH /work-items/{id}` | `update_work_item` |
| Whole plan | `GET /work-items/{id}/tree` | `get_work_item_tree` |
| Dependencies | `GET|POST /work-items/{id}/dependencies`, `DELETE …/{dependencyId}`; `GET …/dependency-graph` | `add_dependency`, `remove_dependency` |
| Criteria | `GET|PUT /work-items/{id}/criteria`, `PATCH …/{criterionId}` | `set_criteria`, `mark_criterion_met` |
| Milestones | `GET|POST /milestones`, `PATCH|DELETE /milestones/{id}` | `list_milestones`, `set_milestone` |
| Agent liveness | `PUT /work-items/{id}/agent-sessions/{sessionKey}` | `report_agent_activity` |
| Ready queue | `GET /work-items?rootId=…&ready=true&sort=priority` | `list_work_items({rootId, ready: true})` |
| Forecast | `GET /work-items/{id}/forecast` | `get_forecast` |
| Health | `GET|POST /work-items/{id}/health-updates` (`{health, note, draft}`), `POST …/health-updates/{updateId}/confirm` | `draft_health_update` (drafts only; a person confirms in the web app) |
| Overview | `GET /api/v2/projects/{id}/delivery-overview` | none (web only) |

## `tasks.json` compatibility

`/conductor:implement` historically wrote `.conductor/issues/{id}/tasks.json`, which the CLI daemon mirrors
into `work_items.work_item_tasks`. That path still works unchanged. Once a PRD has child Work Items,
`tasks.json` becomes a **mirror generated from the tree** (`schema_version: 3`, each task carrying its
`workItemId`); the implement command prefers children when `childCount > 0` and the web app prefers the
server rollup over the legacy checklist in the same case.

## Forecast

`GET /work-items/{id}/forecast` answers "when will the open work under this item be done", computed on read
by `ThroughputForecastService` (nothing stored, no cache; the simulation is about 100k draws).

1. **Remaining** = open leaf items in the subtree (the same rollup as `progress`; cancelled leaves count as
   done). None left is `state: DONE`; an item with no sub-items is `NO_PLAN`.
2. **Samples** = leaf `TASK` items in the subtree that completed in the last 90 days, excluding
   `CANCELLED`/`CLOSED`. Under 8, it widens to the whole project; still under 8 is `INSUFFICIENT_DATA`
   (`samples`, `needed: 8`).
3. **Weekly throughput**: full 7-day buckets counted backwards from now (the newest is `[now-7d, now)`), so
   the current partial week is never a short bucket; the oldest partial window is dropped. The number of
   buckets is the whole weeks between the first sample and now. With **3 or more full weeks** it forecasts by
   throughput; with fewer it falls back to cycle time.
4. **Throughput mode**: a 2 000-run bootstrap draws a week from the series with replacement until the
   cumulative count covers `remaining` (cap 52 weeks). P50 and P85 of the weeks needed become dates, whole
   weeks from today. `weeklyThroughputMedian` is the median of the series.
5. **Cycle-time mode** (P50 only, no `p85Date`): median(`completed_at - started_at`, items with both stamps)
   x remaining / `w`, where `w` is the number of distinct agent sessions `WORKING` under the item in the
   last 7 days (minimum 1). No sample with both stamps is `INSUFFICIENT_DATA`.

`appetiteEndsOn` = the item's `started_at` (its `created_at` if it never started) + `appetite_days`.
`appetiteFit` compares the forecast with it, and `milestoneFit` with the item's milestone `target_on`:
`FITS` (P85 on or before), `TIGHT` (P50 fits, P85 does not) or `EXCEEDS` (P50 is past it). Cycle-time mode
has only a P50, so it is `FITS` or `EXCEEDS`. `appetiteFit` needs the item to have started; before that only
`appetiteEndsOn` is shown.

## Health

A PRD's health is `ON_TRACK`, `AT_RISK` or `OFF_TRACK` plus a note, in `work_item_health_updates`.

- **Draft, then confirm.** An agent calls `draft_health_update` (`POST …/health-updates` with `draft: true`),
  which records a *pending draft* attributed to the caller. Only a person confirms
  (`POST …/health-updates/{id}/confirm`, optionally editing `health`/`note`); a person may also record an
  update directly (`draft: false`). A project key or run token can only draft: confirming or recording a
  confirmed update gets a 403, the same line as other "a person did this" actions (user JWT or user API key
  counts as a person).
- **Current** health is the latest *confirmed* update. A newer unconfirmed one is `pendingDraft`
  (`{updateId, health, note, draftedByLabel, createdAt}`) and never replaces it.
- **Expiry.** A draft older than 48 hours is ignored on read (and cannot be confirmed, 409); it is not deleted.
- **Stale.** The confirmed update is older than 7 days while the item's status category is `in_progress`.
- **atRisk** (a boolean on the health object): the confirmed health is `AT_RISK` or `OFF_TRACK`, **or** it is
  stale, **or** the forecast says the work will overrun (`appetiteFit` or `milestoneFit` is `TIGHT` or
  `EXCEEDS`). The forecast leg is computed on a single Work Item read and in the overview; lists and the tree
  carry the first two legs only. `health` is null on an item with nothing to say.

The `WorkItemHealth` object on `WorkItemResponse` and tree nodes is
`{health, note, updatedAt, stale, pendingDraft, atRisk}`.

## Delivery overview

`GET /api/v2/projects/{id}/delivery-overview` is the Overview page in one call: `activeItems` (each
`{item, progress, health, forecast, signals}`), `milestones` (with progress) and
`attention: {blocked, stale, needsInput}`. Active items are the non-terminal top-level `PRD`s plus any
top-level item that has sub-items. The attention lists cover the whole tree (tasks included) but skip
finished items and plain childless top-level items such as Posts; `needsInput` lists the item that asked, not
the PRD above it. One forecast is computed per active item.

## Migrations and follow-ups

Schema: V179–V184 (delivery columns, milestones, dependencies, criteria, agent sessions, the `TASK` Workflow
seed) and V185 (health updates). Not built yet: `NEEDS_INPUT` and health-changed notification signals, and a
parent filter on the coordinator's agent-facing Work Item list.
