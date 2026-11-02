---
name: conductor:breakdown
description: Break a Conductor PRD into dependency-ordered child tasks — each with acceptance criteria tied to the PRD's AC ids — after a critic review of scope and coverage; also sets the milestone and appetite and writes the tasks.json mirror that /conductor:implement batches from.
allowed-tools: mcp__conductor__*, AskUserQuestion, Agent, Read, Write, Glob, Grep, Bash
---

# /conductor:breakdown

You turn an approved PRD into a plan: child Work Items with dependencies and acceptance criteria. The plan lives in Conductor (the Plan tab, the ready queue, the forecast); `tasks.json` is only a mirror of it.

## Trigger

This skill runs when the user invokes `/conductor:breakdown [issueId]`.

---

## Step 1 — PRD Resolution

**If an explicit argument was provided**, resolve it based on its format:

- **Display ID** (matches `/^[A-Z]+-\d+$/`, e.g. `COND-42`): call `list_work_items({parentId: "any"})` and find the item where `displayId === argument`
- **Bare number** (matches `/^\d+$/`): call `list_work_items({parentId: "any"})` and find the item where `sequenceNumber == argument`
- **UUID**: use directly as `issueId`; take `displayId` from `get_work_item`

`list_work_items` returns top-level items only unless `parentId` is set, so pass `parentId: "any"` whenever the argument could be a task.

**If no argument was provided**: use an issue already discussed in the conversation (for example one `/conductor:prd` just saved); otherwise list recent items with `list_work_items` and ask with AskUserQuestion which PRD to break down.

Store `issueId` (UUID) and `displayId`.

---

## Step 2 — PRD Context Loading

1. If the PRD content is already in the conversation, skip the read.
2. Otherwise call `get_work_item({issueId})` — note `absolutePath` (the issue directory on disk, used for every Read/Write below) and any `appetite`. Read `{absolutePath}prd.html` if it exists, otherwise `{absolutePath}prd.md`.
3. Acceptance-criteria IDs: for `prd.html`, take them from the `data-ac` attributes (`<li data-ac="AC-P0-1.1">`, each inside the `<article data-feature>` it belongs to); for `prd.md`, use the checkbox regex ``^- \[[ x]\] `AC-P[01]-\d+\.\d+` ``. Keep the full list — every one must end up covered by a task.

---

## Step 3 — Existing Plan Check

Call `get_work_item_tree({issueId})`. If the PRD has no children, continue to Step 4.

If it has children, summarise them (count, how many DONE) and use AskUserQuestion:

```json
{
  "questions": [{
    "question": "This PRD already has {N} child tasks ({D} done). What would you like to do?",
    "header": "Existing plan",
    "options": [
      {"label": "Replace", "description": "Cancel or delete the tasks that are not DONE, then rebuild the plan"},
      {"label": "Add missing", "description": "Only create tasks for acceptance criteria no existing task covers"},
      {"label": "Keep", "description": "Leave the plan as it is and stop"}
    ],
    "multiSelect": false
  }]
}
```

- **Keep**: stop.
- **Replace**: for every child (at any depth) whose status is not `DONE`, cancel it with `transition_work_item` (use `get_available_transitions` when the edge is not obvious) or delete it. Never touch DONE tasks; treat the ACs they cover as already covered.
- **Add missing**: collect the `externalRef` of every existing criterion in the tree; the ACs not in that set are the only ones to plan in Step 4. Reuse existing task ids as `blockedBy` targets where a new task depends on them.

---

## Step 4 — Propose the Breakdown

### Breakdown Analysis

Analyze the PRD features and group them into epics:
- Each P0 feature → 1–2 epics
- Shared infrastructure (auth, schema, config) → separate epic
- Consider technical ordering: schema → service → API → UI

For each epic, identify 3–7 tasks:
- Start with data/schema tasks
- Then business logic / service layer
- Then API/interface layer
- Finally wiring / integration

For each task, define 1–3 acceptance criteria with type `auto` (verifiable by test) or `manual` (requires human check).

**Plan shape in Conductor:**
- Create **epics only when there are more than 12 tasks**; otherwise tasks hang directly off the PRD. An epic is just a child Work Item that has children of its own.
- Every task has 1–3 criteria tagged `auto` or `manual`, and each criterion's `externalRef` is the PRD AC id it covers (for example `AC-P0-1.1`). Every PRD AC must be covered by at least one criterion.
- `blockedBy` follows technical order (schema → service → API → UI) and names tasks that must finish first.
- Each task also gets `files_owned`, `contract` (`input`, `output`, `invariant`), `test_plan` and `covers_acs` for the mirror in Step 8.

This is a proposal only — nothing is created until Step 6.

---

## Step 5 — Critic Review

Spawn the `conductor-critic` subagent (`subagent_type: "conductor-critic"`) with: the `issueId` and PRD path, the AC list, the appetite (from `get_work_item`, or "none"), and the proposed task list (titles plus the AC ids each covers).

Show the verdict and mention that `critique.md` is now on the PRD, then:

- **PROCEED**: continue.
- **SPLIT**: show the split proposal and use AskUserQuestion: **Stop and split the PRD** / **Continue with one PRD**. On stop, end here and suggest one PRD per proposed slice.
- **CLARIFY**: list the questions or uncovered ACs. Stop unless the user says to continue (revise the proposal first if the gap was only a missing task, and re-run the critic).

### Confidence-Based Autonomy

| Confidence | Behavior |
|------------|----------|
| **High (>80%)** | Show proposed structure inline, proceed immediately |
| **Medium/Low** | Show proposed structure, use AskUserQuestion to confirm before writing |

**High confidence indicators**: clear P0 feature list, obvious epic groupings, standard stack patterns, no conflicts.

**Low confidence indicators**: vague requirements, multiple valid structures, unfamiliar domain.

When confident, announce and proceed:
```
## Task Breakdown

Creating 4 epics in dependency order:
- E1: Database Schema (foundation)
- E2: Service Layer (depends on E1)
- E3: REST API (depends on E2)
- E4: Frontend (depends on E3)

Creating the work items...
```

When uncertain, ask for confirmation before writing.

---

## Step 6 — Create the Work Items

Create in dependency order so every blocker id exists before it is referenced; epics first (an epic is a child of the PRD whose own children are its tasks).

```
create_work_item({
  parentWorkItemId: <PRD id, or the epic id>,
  title, description, priority: "P0" | "P1" | "P2" | "P3",
  criteria: [{text, kind: "auto" | "manual", externalRef: "AC-P0-1.1"}],
  blockedBy: [<ids of tasks that must finish first>]
})
```

Priority follows the feature tier (P0 feature → `P0`, P1 → `P1`). Record each returned `id` and `displayId`. After each call, check for a `warning` field; if present, surface it and pause:

> ⚠️ Create failed (queued): {warning}
> Run `conductor start` to drain the sync queue, then verify in the UI before continuing.

Use AskUserQuestion with **Continue anyway** / **Abort**.

---

## Step 7 — Milestone and Appetite

1. Call `list_milestones`.
2. Propose a milestone named after the PRD title. When the PRD has an appetite, set `targetOn` to today + the appetite in days (week = 7, month = 30).
3. AskUserQuestion: **Create** (`set_milestone({name, targetOn})`) / **Use existing** (pick one of the listed milestones) / **None**.
4. Call `update_work_item({issueId, milestoneId, appetite})` on the PRD — `milestoneId` only if one was chosen, `appetite` only if the PRD has none yet and the user gave one (accepted forms: `2 weeks`, `10 days`, `1 month`). Children inherit the milestone.

---

## Step 8 — Write the tasks.json Mirror

Write `{absolutePath}tasks.json` with the Write tool. It mirrors the tree you just created (the CLI daemon syncs it for offline checklists), and `/conductor:implement` reads `files_owned` from it to batch tasks without file conflicts, so keep `files_owned`, `contract`, `test_plan` and `covers_acs` accurate. Schema version 3 is the v2 shape plus `workItemId` on every task and epic:

```json
{
  "issue_id": "{issueId}",
  "issue_title": "{PRD title}",
  "created_at": "{ISO timestamp}",
  "schema_version": 3,
  "status": "PENDING",
  "epics": [
    {
      "id": "E1",
      "workItemId": "{epic work item id, or null when tasks hang off the PRD}",
      "title": "Epic Title",
      "goal": "One-sentence goal",
      "depends_on": [],
      "status": "PENDING",
      "tasks": [
        {
          "id": "T1.1",
          "workItemId": "{task work item id}",
          "title": "Task title",
          "description": "What to implement and how",
          "priority": "HIGH",
          "complexity": "small",
          "depends_on": [],
          "status": "PENDING",
          "files_owned": ["path/relative/to/repo"],
          "covers_acs": ["AC-P0-1.1"],
          "contract": {"input": "...", "output": "...", "invariant": "..."},
          "test_plan": ["Short test description"],
          "criteria": [{"text": "Specific verifiable outcome", "type": "auto", "met": false}]
        }
      ]
    }
  ]
}
```

With 12 tasks or fewer, use a single epic `E1` with `workItemId: null` holding all tasks. Valid values: `status` — `PENDING | IN_PROGRESS | COMPLETED | BLOCKED`; `priority` — `HIGH | MEDIUM | LOW`; `complexity` — `small | medium | large`; `type` — `auto | manual`.

---

## Step 9 — Report

```
report_step_run({
  issueId, stepKind: "skill", skill: "conductor:breakdown", status: "SUCCEEDED",
  inputBrief: "<one line: what the PRD asked for and the appetite>",
  produced: [{kind: "document", ref: "tasks.json"}]
})
```

Then print a summary and suggest `/conductor:implement {displayId}`:

```
## Breakdown Complete

PRD: {displayId} — {title}   Milestone: {name | none}   Appetite: {label | none}
Critic: {PROCEED | SPLIT | CLARIFY} (critique.md)

| Epic / Task | ID | Priority | Blocked by | ACs |
|---|---|---|---|---|
| E1 Database schema | COND-143 | P0 | — | — |
| T1.1 Add reviews table | COND-144 | P0 | — | AC-P0-1.1 |
```
