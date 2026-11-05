---
name: conductor:implement
description: Take a Conductor PRD issue to a green PR — advances it to IN_PROGRESS through the correct status transitions, works the plan created by /conductor:breakdown (verifying each task's criteria), sets up a git branch, spawns parallel coding subagents, creates a PR, marks it CODE_REVIEW, and monitors CI (two checks max).
allowed-tools: mcp__conductor__*, AskUserQuestion, Agent, Read, Write, Glob, Grep, Bash, ScheduleWakeup, TaskCreate, TaskUpdate
---

# /conductor:implement

You are the Conductor implementation orchestrator. Your job is to take a PRD issue from `READY_FOR_DEVELOPMENT` to a merged-ready PR with minimal friction.

## Trigger

This skill runs when the user invokes `/conductor:implement [issueId]`.

---

## Step 1 — Issue Resolution

Determine which Conductor issue to implement.

**If an explicit argument was provided**, resolve it based on its format:

- **Display ID** (matches `/^[A-Z]+-\d+$/`, e.g. `COND-42`):
  - Call `list_work_items` and find the issue where `displayId === argument`
  - Store both `issueId` (UUID) and `displayId` (e.g. `COND-42`)

- **Bare number** (matches `/^\d+$/`, e.g. `42`):
  - Call `list_work_items` and find the issue where `sequenceNumber == argument`
  - Store both `issueId` (UUID) and `displayId`

- **UUID** (fallback, e.g. `cdbc04d1-...`):
  - Use directly as `issueId`
  - Set `displayId` to the `displayId` field returned from `get_work_item`

**If no argument was provided**:
- Check if a Conductor issue was already discussed in the current conversation (issue ID mentioned, PRD content shown, etc.). If yes, use that issue ID.
- If nothing is inferable, use `list_work_items` to fetch recent issues, then use AskUserQuestion:
  ```json
  {
    "questions": [{
      "question": "Which issue would you like to implement?",
      "header": "Select Issue",
      "options": [{"label": "{title}", "description": "{displayId} — {status}"}],
      "multiSelect": false
    }]
  }
  ```

Store both `issueId` (UUID) and `displayId` (e.g. `COND-42`) for use throughout the rest of the skill.

---

## Step 2 — PRD Context Loading

Load the PRD if not already in context.

1. **If the PRD content is already present in the conversation** (e.g. user just ran `/conductor:prd`): skip the file read entirely.

2. **If the PRD is not in context**:
   - Call `get_work_item` with the resolved `issueId` — note the returned `absolutePath` (the issue directory on disk); use it for any subsequent `Read` / `Write` / `Edit` calls in this skill (the `Read`, `Write`, and `Edit` tools require absolute paths)
   - Read the local PRD at `{absolutePath}prd.html` if it exists, otherwise `{absolutePath}prd.md`. Remember which one exists as `prdPath` (the full absolute path) and pass it to coders
   - Acceptance-criteria IDs: for `prd.html`, take them from the `data-ac` attributes (`<li data-ac="AC-P0-1.1">`; each sits inside the `<article data-feature>` it belongs to); for `prd.md`, use the checkbox regex ``^- \[[ x]\] `AC-P[01]-\d+\.\d+` ``

---

## Step 2b — Advance to IN_PROGRESS

Walk the Work Item to `IN_PROGRESS` **driven by the Workflow definition**, not a hardcoded graph — so this
works for any Workflow, not only Engineering. Loop:

1. Call `get_available_transitions({issueId})` → the valid next statuses for this Work Item right now
   (`{workflow, currentStatus, noun, transitions: [{toStatus, label, requiresReview}]}`).
2. If `currentStatus` is already `IN_PROGRESS`, stop — you're ready to implement.
3. If `currentStatus` is terminal or `CODE_REVIEW` (no path forward toward IN_PROGRESS), the issue may already
   be implemented — use AskUserQuestion with options **Continue anyway** / **Abort**.
4. Otherwise pick the transition whose `toStatus` advances toward `IN_PROGRESS`
   (the typical path is `DRAFT → IN_REVIEW → READY_FOR_DEVELOPMENT → IN_PROGRESS`), call
   `transition_work_item({issueId, toStatus})`, then repeat from step 1.

Never call a status that isn't in the returned `transitions` list — the backend rejects an invalid or
review-gated move. After each `transition_work_item` call, check for a `warning` field (sync queued); if
present, surface it and pause:

> ⚠️ Status update failed (queued): {warning}
> Run `conductor start` to drain the sync queue, then verify the status in the UI before continuing.

Use AskUserQuestion with options: **Continue anyway** / **Abort**.

---

## Step 3 — Plan source

Call `get_work_item_tree({issueId})`.

**If the PRD has children** (the plan lives in Conductor, created by `/conductor:breakdown`): the work queue is the ready queue —
`list_work_items({rootId: issueId, ready: true, sort: "priority", parentId: "any"})` — re-queried after each batch in Step 5. Done tasks are skipped automatically; resuming needs no choice. If `{absolutePath}tasks.json` exists (schema_version 3), read it for each task's `files_owned`, `contract`, `test_plan` and `covers_acs`, matched by `workItemId`. Skip to Step 4.

**If the PRD has no children**: print

> No plan yet — run /conductor:breakdown first

then fall through to the legacy flow, which still works for a `tasks.json` that already exists at `{absolutePath}tasks.json` (where `absolutePath` was returned by `get_work_item` in Step 2; the Read/Write/Edit tools require absolute paths):

- **It exists**: read it (schema_version 1 or 2 run as before) and skip COMPLETED tasks, continuing from the first PENDING one.
- **It does not exist**: stop and tell the user to run `/conductor:breakdown {displayId}`. Breakdown analysis, confidence rules and the `tasks.json` schema live in `.claude/commands/conductor/breakdown.md`; this command no longer generates a plan.

---

## Step 4 — Git Branch Setup

Before any implementation begins, set up the feature branch.

1. **Check working tree**: run `git status --porcelain`
   - If dirty, use AskUserQuestion: **Commit changes** / **Stash changes** / **Abort**
     - Commit: `git add -A && git commit -m "wip: stash before conductor:implement"`
     - Stash: `git stash`
     - Abort: stop here

2. **Fetch latest main**: `git fetch origin main`

3. **Check for existing branch**: `git branch --list feat/{displayId}`
   - If branch exists, use AskUserQuestion: **Continue on existing branch** / **Recreate from main**
     - Recreate: `git checkout main && git pull origin main && git branch -D {branch} && git checkout -b {branch}`
     - Continue: `git checkout {branch}`
   - If branch does not exist: `git checkout -b feat/{displayId} origin/main`

4. Print confirmation:
   ```
   ✓ Working tree clean
   ✓ Fetched origin/main
   ✓ Branch feat/{displayId} created and checked out
   ```

---

## Step 5 — Implementation Orchestration

### Work Queue

**Tree plan (children exist):** take the ready queue from Step 3. Each row is a task; use `get_work_item({issueId: task})` for its description and criteria (`criterionId`, `text`, `kind`, `externalRef`). Tasks with unfinished blockers are not in the queue; they appear after their blockers reach DONE, so re-query `list_work_items({rootId, ready: true, sort: "priority", parentId: "any"})` after each batch until the queue is empty. Epics (tasks that have children) are completed by the system once their children finish; do not dispatch them.

**Legacy tasks.json plan:** read `{absolutePath}tasks.json` and build an ordered list:
1. Sort epics by dependency order (topological sort on `depends_on`)
2. Within each epic, sort tasks by dependency order, then by priority (HIGH → MEDIUM → LOW)
3. Mark tasks with unresolved dependencies as BLOCKED

**Resume mode** (if tasks.json had completed tasks): skip all COMPLETED tasks and print:
> Resuming — {N} tasks already completed, starting from {task.id}

### Batch Execution

Group independent tasks (no unresolved dependencies, no file-level conflicts) into parallel batches:

| Complexity | Max parallel |
|------------|-------------|
| `small` | 4 |
| `medium` | 3 |
| `large` | 2 |
| mixed batch | cap at heaviest task in batch |

Tasks with `depends_on` within the same epic always run sequentially (wait for dependency to complete first).

### File-conflict check

Before dispatching a batch, compute the union of `files_owned` across tasks in the batch. If any path appears more than once, split the batch into sequential sub-batches.

When splitting, place the conflicting tasks in different sub-batches such that each sub-batch's `files_owned` union has no duplicates; sub-batches then run one after another (the second sub-batch waits for the first to complete).

For tree plans, take `files_owned` from the tasks.json mirror (matched by `workItemId`); a task with no entry there is assumed to conflict with nothing.

For v1 tasks (no top-level `schema_version`, or no `files_owned` on tasks), behavior falls back to the existing heuristic-based grouping — the file-conflict check is skipped and v1 batches are formed from the complexity table above without change.

### Subagent Dispatch

**Per task, before dispatch (tree plans):**

```
transition_work_item({issueId: <task>, toStatus: "IN_PROGRESS"})      // TODO → IN_PROGRESS
report_agent_activity({issueId: <task>, sessionKey: <stable key, e.g. "implement-{displayId}-{task displayId}">,
                       mode: "DELEGATE", activity: "WORKING"})
```

For each batch, spawn `conductor-coder` subagents in parallel using the Agent tool, all calls in one message, each with `subagent_type: "conductor-coder"`. The agent (`.claude/agents/conductor-coder.md`) is self-contained and handles stack detection internally. Pass each agent this prompt:

```
# Task: {task.id} - {task.title}

## Description
{task.description}

## Acceptance Criteria
{for each criterion: "- [{type}] {text}"}

## Context
- Epic: {epic.id} - {epic.title}
- Issue: {issue_id} - {issue_title}
- workItemId: {task work item id, tree plans only}
- sessionKey: {the key used above, tree plans only}
- Full PRD: {prdPath} (prd.html or prd.md, whichever exists; read if more context needed)

## Workflow
1. Write tests for each [auto] criterion FIRST
2. Implement until all tests pass
3. Document [manual] criteria verification steps
4. Commit: "{task.id}: {brief description}" with acceptance criteria in body
5. If you hit a decision you cannot make from the task and the PRD, report NEEDS_INPUT on workItemId and stop BLOCKED
6. Report: COMPLETED or BLOCKED (with reason)
```

**After each coder reports (tree plans):**

1. If COMPLETED, spawn `conductor-verifier` (`subagent_type: "conductor-verifier"`) with `workItemId`, `sessionKey`, the task's criteria (`criterionId`, `text`, `kind`, `externalRef`) and its `test_plan`. It records evidence with `mark_criterion_met` and ends with `report_agent_activity(DONE|ERRORED)`.
2. Re-read the criteria (`get_work_item_tree({issueId: task})`). Only when **every `auto` criterion is met**, call `transition_work_item({issueId: task, toStatus: "DONE"})` (`IN_PROGRESS → DONE`). Otherwise leave the task IN_PROGRESS and list it under Known gaps with the unmet criteria.
3. If the coder reported BLOCKED, it has already reported `NEEDS_INPUT` when a decision was missing; otherwise call `report_agent_activity({issueId: task, sessionKey, activity: "ERRORED", note: <blocker>})`. Leave the task IN_PROGRESS and list it under Known gaps.

Check every `transition_work_item` response for a `warning` field and surface it as in Step 2b.

### After Each Batch

Tree plans: update the status of each dispatched task in the tasks.json mirror when it exists (`COMPLETED` or `BLOCKED`, `met` on passing criteria), then re-query the ready queue for the next batch.

Legacy plans: update `tasks.json` using the Edit tool:
- Set `status: COMPLETED` or `status: BLOCKED` on each task
- Set `met: true` on criteria that passed
- Update epic `status` based on its tasks' statuses
- Update top-level `status` field

---

## Step 6 — PR Creation

After all non-blocked tasks complete, push the branch and create the PR.

```bash
git push -u origin feat/{displayId}
```

Then create the PR:

```bash
gh pr create --title "feat({displayId}): {issue title trimmed to 72 chars}" --body "$(cat <<'EOF'
## What
- {Epic 1 title}: {goal}
- {Epic 2 title}: {goal}
{...one bullet per epic}

## How
{2-5 bullets: key implementation decisions extracted from commit messages — non-obvious choices only}

## Testing
- {N} automated criteria passing
- Manual verification needed:
  - [ ] {manual criterion text} → {verification steps}
  {repeat for each manual criterion}

{if blocked tasks exist:}
## Known gaps
- {task.id}: {blocker reason}
{end if}

Closes conductor/{displayId}
EOF
)"
```

Show the PR URL to the user.

---

## Step 7 — Record the PR, report the run, advance to CODE_REVIEW

After the PR is created successfully:

1. **Record the PR as an Asset** (supersedes the old `github_pr_url` column):

   ```
   record_asset({issueId, type: "github_pr", kind: "link", ref: <pr_url>, label: "Pull Request", done: true})
   ```

2. **Report the step-run** so the human Review gate can judge what you did (P0-6):

   ```
   report_step_run({
     issueId, stepKind: "skill", skill: "conductor:implement", status: "AWAITING_REVIEW",
     reportedBy: <your conductor user/email>, fromStatus: "IN_PROGRESS", toStatus: "CODE_REVIEW",
     inputBrief: "<one-line: what this issue asked you to implement>",
     produced: [{kind: "asset", ref: <pr_url>, label: "PR", assetType: "github_pr"}]
   })
   ```

3. **Draft the health update** (tree plans): one paragraph on where delivery stands, for the owner to confirm in the web app:

   ```
   draft_health_update({issueId, health: "ON_TRACK" | "AT_RISK", note: "<what is done, what is not, and why>"})
   ```

   `ON_TRACK` when every task reached DONE; `AT_RISK` when any task is a Known gap.

4. **Advance to CODE_REVIEW** (definition-driven — confirm `CODE_REVIEW` is in the available transitions first):

   ```
   get_available_transitions({issueId})           // expect CODE_REVIEW among the options from IN_PROGRESS
   transition_work_item({issueId, toStatus: "CODE_REVIEW"})
   ```

   **Stop here.** `CODE_REVIEW → DONE` is a server-enforced Review gate — a human approves the PR; do not
   attempt to move the Work Item to DONE yourself (the PR merge completes it).

**Check each response**: if it contains a `warning` field, surface it to the user:

> ⚠️ Update failed (queued): {warning}
> Run `conductor start` to drain the sync queue, or update the status manually in the UI.

---

## Step 8 — CI Monitoring (Two-Check Maximum)

**Check 1** — run immediately after `gh pr create`:

```bash
gh pr checks {pr_url}
```

- **All green**: report results and stop. Done.
- **Any pending or running**: proceed to Check 2 scheduling.

**Check 2** — schedule exactly one wakeup:

```
ScheduleWakeup(delaySeconds=240, reason="Final CI check for {pr_url}", prompt="<<current /conductor:implement prompt>>")
```

On wake, run `gh pr checks {pr_url}` one final time.

Report each check: name, status (pass/fail/pending), and the PR URL.

If checks are **still pending** on this second call:
> CI still running — check manually: {pr_url}

**Never schedule a third check.** Stop after the second check regardless of result.

---

## Final Summary

After everything completes (or after the final CI report), print:

```
## Implementation Complete

Issue: {displayId} — {issue_title}
Branch: feat/{displayId}
PR: {pr_url}

Tasks:
- {N} completed
- {M} blocked (see Known gaps in PR)

CI: {all green | still running — check manually: {pr_url}}
```
