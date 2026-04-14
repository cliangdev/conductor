---
name: conductor:implement
description: Take a Conductor PRD issue from approved to a green PR — resolves the issue, writes tasks.yaml breakdown, sets up a git branch, spawns parallel coding subagents, creates a PR, and monitors CI (two checks max).
allowed-tools: mcp__conductor__*, AskUserQuestion, Agent, Read, Write, Glob, Grep, Bash, ScheduleWakeup, TaskCreate, TaskUpdate
---

# /conductor:implement

You are the Conductor implementation orchestrator. Your job is to take a PRD issue from approved to a merged-ready PR with minimal friction.

## Trigger

This skill runs when the user invokes `/conductor:implement [issueId]`.

---

## Step 1 — Issue Resolution

Determine which Conductor issue to implement.

**If an explicit argument was provided** (e.g. `/conductor:implement cdbc04d1-...` or `/conductor:implement 42`):
- Use that ID directly.

**If no argument was provided**:
- Check if a Conductor issue was already discussed in the current conversation (issue ID mentioned, PRD content shown, etc.). If yes, use that issue ID.
- If nothing is inferable, use `list_issues` to fetch recent issues, then use AskUserQuestion:
  ```json
  {
    "questions": [{
      "question": "Which issue would you like to implement?",
      "header": "Select Issue",
      "options": [{"label": "{title}", "description": "{issueId} — {status}"}],
      "multiSelect": false
    }]
  }
  ```

Store `issueId` for use throughout the rest of the skill.

---

## Step 2 — PRD Context Loading

Load the PRD if not already in context.

1. **If the PRD content is already present in the conversation** (e.g. user just ran `/conductor:prd`): skip the file read entirely.

2. **If the PRD is not in context**:
   - Call `get_issue` with the resolved `issueId`
   - Read the local file: `.conductor/issues/{issueId}/prd.md`

3. **Status check**: If the issue status is not `APPROVED` or `IN_REVIEW`, warn the user:
   > ⚠️ Issue is in `{status}` status (expected APPROVED or IN_REVIEW). Continue anyway?

   Use AskUserQuestion with options: **Continue** / **Abort**.

---

## Step 3 — YAML Task Breakdown

Check if `.conductor/issues/{issueId}/tasks.yaml` already exists.

**If it exists**: use AskUserQuestion:
```json
{
  "questions": [{
    "question": "tasks.yaml already exists for this issue. What would you like to do?",
    "header": "Resume or Regenerate",
    "options": [
      {"label": "Resume", "description": "Skip completed tasks, continue from the first PENDING task"},
      {"label": "Regenerate", "description": "Delete existing breakdown and recreate from scratch"}
    ],
    "multiSelect": false
  }]
}
```
- **Resume**: read the file, note which tasks are COMPLETED, skip to Step 4.
- **Regenerate**: delete the existing file (Bash `rm`), continue with breakdown below.

**If it does not exist** (or after regenerate): analyze the PRD and produce the breakdown.

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

Writing tasks.yaml...
```

When uncertain, ask for confirmation before writing.

### Writing tasks.yaml

1. Call `scaffold_document` with `issueId` and `filename: "tasks.yaml"` — use the returned `localPath`.
2. Write the file using the Write tool with this schema:

```yaml
issue_id: "{issueId}"
issue_title: "{issue title from PRD}"
created_at: "{ISO timestamp}"
status: PENDING   # PENDING | IN_PROGRESS | COMPLETED

epics:
  - id: E1
    title: "Epic Title"
    goal: "One-sentence goal"
    depends_on: []          # list of epic ids this depends on
    status: PENDING         # PENDING | IN_PROGRESS | COMPLETED | BLOCKED
    tasks:
      - id: T1.1
        title: "Task title"
        description: "What to implement and how"
        priority: HIGH      # HIGH | MEDIUM | LOW
        complexity: small   # small | medium | large
        depends_on: []      # list of task ids within this epic
        status: PENDING     # PENDING | IN_PROGRESS | COMPLETED | BLOCKED
        criteria:
          - text: "Specific verifiable outcome"
            type: auto      # auto | manual
            met: false
```

---

## Step 4 — Git Branch Setup

Before any implementation begins, set up the feature branch.

1. **Check working tree**: run `git status --porcelain`
   - If dirty, use AskUserQuestion: **Commit changes** / **Stash changes** / **Abort**
     - Commit: `git add -A && git commit -m "wip: stash before conductor:implement"`
     - Stash: `git stash`
     - Abort: stop here

2. **Fetch latest main**: `git fetch origin main`

3. **Check for existing branch**: `git branch --list feat/CONDUCTOR-{issueId-short}`
   - Use the first 8 characters of the issueId as the short form (or full UUID if preferred — use whatever is conventional in the repo from `git branch -a`)
   - Check existing branches with `git branch -a | grep conductor` to learn the convention
   - If branch exists, use AskUserQuestion: **Continue on existing branch** / **Recreate from main**
     - Recreate: `git checkout main && git pull origin main && git branch -D {branch} && git checkout -b {branch}`
     - Continue: `git checkout {branch}`
   - If branch does not exist: `git checkout -b feat/CONDUCTOR-{issueId-short} origin/main`

4. Print confirmation:
   ```
   ✓ Working tree clean
   ✓ Fetched origin/main
   ✓ Branch feat/CONDUCTOR-{issueId-short} created and checked out
   ```

---

## Step 5 — Implementation Orchestration

Read `tasks.yaml` (or use the in-memory breakdown from Step 3).

### Work Queue

Build an ordered list of tasks:
1. Sort epics by dependency order (topological sort on `depends_on`)
2. Within each epic, sort tasks by dependency order, then by priority (HIGH → MEDIUM → LOW)
3. Mark tasks with unresolved dependencies as BLOCKED

**Resume mode** (if tasks.yaml had completed tasks): skip all COMPLETED tasks and print:
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

### Subagent Dispatch

For each batch, spawn `flux-coder` subagents in parallel using the Agent tool. Pass each agent this prompt:

```
# Task: {task.id} - {task.title}

## Description
{task.description}

## Acceptance Criteria
{for each criterion: "- [{type}] {text}"}

## Context
- Epic: {epic.id} - {epic.title}
- Issue: {issue_id} - {issue_title}
- Full PRD: .conductor/issues/{issue_id}/prd.md (read if more context needed)

## Project Skills
{if .claude/skills/ contains a relevant skill: paste content of matching SKILL.md}
{otherwise: "Use general best practices for this project's stack"}

## Workflow
1. Apply project skill patterns above
2. Write tests for each [auto] criterion FIRST
3. Implement until all tests pass
4. Document [manual] criteria verification steps
5. Commit: "{task.id}: {brief description}" with acceptance criteria in body
6. Report: COMPLETED or BLOCKED (with reason)
```

### After Each Batch

Update `tasks.yaml` using the Edit tool:
- Set `status: COMPLETED` or `status: BLOCKED` on each task
- Set `met: true` on criteria that passed
- Update epic `status` based on its tasks' statuses
- Update top-level `status` field

---

## Step 6 — PR Creation

After all non-blocked tasks complete, push the branch and create the PR.

```bash
git push -u origin feat/CONDUCTOR-{issueId-short}
```

Then create the PR:

```bash
gh pr create --title "feat: {issue title trimmed to 72 chars}" --body "$(cat <<'EOF'
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

Closes #{issueId}
EOF
)"
```

Show the PR URL to the user.

---

## Step 7 — Issue Status Update (P1)

After the PR is created successfully, call:

```
set_issue_status(issueId, "IN_REVIEW")
```

This transitions the Conductor issue from `APPROVED` → `IN_REVIEW`.

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

Issue: {issue_title}
Branch: feat/CONDUCTOR-{issueId-short}
PR: {pr_url}

Tasks:
- {N} completed
- {M} blocked (see Known gaps in PR)

CI: {all green | still running — check manually: {pr_url}}
```
