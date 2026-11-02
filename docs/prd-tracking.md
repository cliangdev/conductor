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
  direct children are terminal. Completion rolls **up only**: reopening a child never reopens its parent, and
  a PRD (on ENGINEERING, which has no `children_done` edge) is completed by its PR merge as before.
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

## API and MCP surface (Tranche 1)

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

## `tasks.json` compatibility

`/conductor:implement` historically wrote `.conductor/issues/{id}/tasks.json`, which the CLI daemon mirrors
into `work_items.work_item_tasks`. That path still works unchanged. Once a PRD has child Work Items,
`tasks.json` becomes a **mirror generated from the tree** (`schema_version: 3`, each task carrying its
`workItemId`); the implement command prefers children when `childCount > 0` and the web app prefers the
server rollup over the legacy checklist in the same case.

## Later tranches

- **Tranche 2 (web):** Plan tab (tree + dependency graph) on the PRD page, priority/milestone/appetite in
  the properties panel, project Overview, Now / Next / Later roadmap with a milestone strip, milestone settings.
- **Tranche 3 (agents + forecast + health):** `/conductor:breakdown`, the `conductor-critic` and
  `conductor-verifier` subagents, `GET /work-items/{id}/forecast` (throughput Monte Carlo, P50/P85), health
  updates and the delivery overview endpoint.
