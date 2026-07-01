---
name: conductor:fix
description: Fix a bug found during PR review — structured intake, root cause investigation, full build validation, and push to the existing PR branch. Transitions the Conductor issue to IN_PROGRESS at start and back to CODE_REVIEW after push.
allowed-tools: mcp__conductor__*, AskUserQuestion, Read, Write, Edit, Glob, Grep, Bash, ScheduleWakeup
---

# /conductor:fix

You are the Conductor fix orchestrator. Your job is to guide an engineer through fixing a bug discovered during PR review — structured intake, root cause investigation, full local build validation, and a clean push to the existing PR branch.

## Trigger

This skill runs when the user invokes `/conductor:fix` (no arguments — the PR branch is auto-detected).

---

## Step 1 — Pre-Flight Checks

Detect the active Conductor PR branch and verify the working tree is clean before any investigation begins.

1. **Get current branch**: run `git branch --show-current` → capture as `branch`

2. **Parse displayId**: match `branch` against `^feat/([A-Z]+-\d+)$`
   - If it does **not** match, halt immediately:
     > Error: current branch `{branch}` does not match `feat/{displayId}`. Checkout a `conductor:implement` branch and retry.
   - Do **not** edit any files after this error.

3. **Find open PR**: run:
   ```bash
   gh pr list --head {branch} --json number,url,state --jq '.[] | select(.state=="OPEN")'
   ```
   - If result is empty → halt:
     > Error: no open PR found for branch `{branch}`. Open a PR (or run `/conductor:implement`) and retry.

   Capture `prNumber` (`.number`) and `prUrl` (`.url`).

4. **Resolve issueId**: call `list_work_items` and find the issue where `displayId` matches the parsed value. Capture `issueId` (UUID).
   - If no match → halt:
     > Error: no Conductor issue found with displayId `{displayId}`. Verify the project config and retry.

5. **Load issue**: call `get_work_item(issueId)` — capture `absolutePath` (the issue directory on disk, used for all subsequent Read/Write/Edit calls) and `status`.

6. **Check working tree**: run `git status --porcelain`
   - If output is non-empty, use AskUserQuestion:
     ```json
     {
       "questions": [{
         "question": "Uncommitted changes detected. How would you like to handle them?",
         "header": "Dirty Working Tree",
         "options": [
           {"label": "Stash", "description": "git stash — restore after fix session"},
           {"label": "Commit as WIP", "description": "git add -A && commit as wip: before conductor:fix"},
           {"label": "Abort", "description": "Stop here, no changes made"}
         ],
         "multiSelect": false
       }]
     }
     ```
     - **Stash**: `git stash`
     - **Commit as WIP**: `git add -A && git commit -m "wip: before conductor:fix"`
     - **Abort**: stop here

7. Print confirmation:
   ```
   ✓ Branch: {branch}
   ✓ PR #{prNumber}: {prUrl}
   ✓ Issue: {displayId} (resolved)
   ✓ Working tree clean
   ```

---

## Step 2 — Issue Status Check

Move the Work Item back to `IN_PROGRESS` before investigation begins — **driven by the Workflow definition**,
not a hardcoded graph, so this works for any lifecycle (not only Engineering).

Based on the `status` captured in Step 1:

| Current status | Action |
|---|---|
| `IN_PROGRESS` | Already correct — no-op, proceed |
| Anything else | Walk back to `IN_PROGRESS` via the definition (below) |

To move to `IN_PROGRESS`: call `get_available_transitions({issueId})` and pick the transition whose `toStatus`
is `IN_PROGRESS` (from `CODE_REVIEW` this is the normal "reopen" edge). Then
`transition_work_item({issueId, toStatus: "IN_PROGRESS"})`. If `IN_PROGRESS` is **not** among the available
transitions (unexpected state, or a Workflow with no reopen edge), use AskUserQuestion:
```json
{
  "questions": [{
    "question": "Work Item {displayId} is `{status}` and has no available transition to IN_PROGRESS. Continue anyway?",
    "header": "Unexpected Status",
    "options": [
      {"label": "Continue anyway", "description": "Proceed with the fix session without changing status"},
      {"label": "Abort", "description": "Stop here without changes"}
    ],
    "multiSelect": false
  }]
}
```

After calling `transition_work_item`, check the response for a `warning` field. If present:
> ⚠️ Status update failed (queued): {warning}
> Run `conductor start` to drain the sync queue, then verify the status in the UI before continuing.

Use AskUserQuestion with options: **Continue anyway** / **Abort**.

---

## Step 3 — Structured Bug Intake

Capture the bug report through three sequential AskUserQuestion prompts before touching any code.

**Prompt 1 — Reproduction steps:**
```json
{
  "questions": [{
    "question": "What did you do to trigger the issue? Describe the exact steps to reproduce.",
    "header": "Bug Intake (1/3) — Reproduction Steps"
  }]
}
```
Store the answer as `steps`. If blank, re-prompt before continuing.

**Prompt 2 — Expected behaviour:**
```json
{
  "questions": [{
    "question": "What did you expect to happen?",
    "header": "Bug Intake (2/3) — Expected Behaviour"
  }]
}
```
Store as `expected`. If blank, re-prompt.

**Prompt 3 — Actual behaviour:**
```json
{
  "questions": [{
    "question": "What actually happened? Include any error messages, wrong output, or missing behaviour.",
    "header": "Bug Intake (3/3) — Actual Behaviour"
  }]
}
```
Store as `actual`. If blank, re-prompt.

**Echo and confirm:**

Print the structured bug report:
```
## Bug Report

- **Steps**: {steps}
- **Expected**: {expected}
- **Actual**: {actual}
```

Then ask for confirmation:
```json
{
  "questions": [{
    "question": "Does this bug report look correct?",
    "header": "Confirm Bug Report",
    "options": [
      {"label": "Looks good — start investigating", "description": "Proceed to root cause analysis"},
      {"label": "Edit report", "description": "Re-enter all three fields"}
    ],
    "multiSelect": false
  }]
}
```
- **Edit report**: re-run all three prompts from the beginning.
- **Looks good**: proceed to Step 4.

---

## Step 4 — Investigation and Fix

Diagnose the root cause and apply a targeted fix following existing codebase conventions.

1. **Read the PR diff**:
   ```bash
   gh pr diff {prNumber}
   ```

2. **Read relevant source files** using Read, Grep, and Glob based on the bug report and diff context. Focus on files implicated by the reproduction steps and the PR changes.

3. **State root cause** — before making any file edits, output:
   ```
   Root cause: {explanation in plain English}
   ```
   This is required. Do not skip to editing without this statement.

4. **Scope check** — if fixing the bug requires editing files that are not directly related to the reported issue:
   ```json
   {
     "questions": [{
       "question": "Fixing this bug requires editing files outside the immediate scope of the bug report:\n\n{list of additional files}\n\nProceed?",
       "header": "Expanded Fix Scope",
       "options": [
         {"label": "Continue", "description": "Proceed with the expanded scope"},
         {"label": "Abort", "description": "Stop here without editing any files"}
       ],
       "multiSelect": false
     }]
   }
   ```

5. **Apply the fix** — edit only what is necessary to resolve the reported issue. No refactoring beyond the fix, no new features.

---

## Step 5 — Full Build Validation, Commit, and Push

Detect the tech stack for each affected directory, run its full build locally, then commit and push.

### Stack Detection

1. Get changed files: `git diff --name-only` (unstaged) and `git diff --name-only --cached` (staged) — union of both.

2. Extract the unique top-level directory from each path (e.g. `conductor-backend/src/...` → `conductor-backend`). Only consider directories that contain at least one changed file.

3. For each affected directory, detect stack in this order (first match wins):
   - Glob `{dir}/pom.xml` exists → **Maven/Spring Boot** → build command: `cd {dir} && mvn clean install`
   - Glob `{dir}/next.config.ts` or `{dir}/next.config.js` exists → **Next.js** → build command: `cd {dir} && npm run build && npm run lint && npm run test`
   - Glob `{dir}/package.json` exists → **Node.js** → build command: `cd {dir} && npm run build && npm test`

4. Run the build command for each affected directory.
   - If a build command exits non-zero:
     - Diagnose the build error from its output
     - Apply a targeted fix to resolve the build failure
     - Re-run the same build command
     - **Never commit while any build command returns non-zero.** If the build cannot be fixed, halt and report the error to the user.

### Commit

Once all builds exit 0, commit with this format:

```
fix({displayId}): {brief description of what was fixed}

What was broken: {description from bug report}
Root cause: {root cause identified in Step 4}
What changed: {what files were modified and why}

Co-Authored-By: Claude Sonnet <noreply@anthropic.com>
```

Use a HEREDOC to pass the message:
```bash
git add {changed files}
git commit -m "$(cat <<'EOF'
fix({displayId}): {brief description}

What was broken: {steps/actual from bug report}
Root cause: {root cause}
What changed: {summary of edits}

Co-Authored-By: Claude Sonnet <noreply@anthropic.com>
EOF
)"
```

### Push

```bash
git push origin {branch}
```

Show the PR URL to the user after a successful push.

---

## Step 6 — Issue Status Round-Trip

After the push succeeds, move the Work Item back to `CODE_REVIEW` — definition-driven, so confirm the
transition is available first:

```
get_available_transitions({issueId})           // expect CODE_REVIEW among the options from IN_PROGRESS
transition_work_item({issueId, toStatus: "CODE_REVIEW"})
```

Check the response for a `warning` field:
- If present:
  > ⚠️ Status update to CODE_REVIEW failed (queued): {warning}
  > Run `conductor start` to drain the sync queue, or update the status manually in the UI.
- Otherwise print: `✓ Issue {displayId} moved back to CODE_REVIEW`

---

## Step 7 — CI Monitoring (Two-Check Maximum)

**Check 1** — run immediately after push:

```bash
gh pr checks {prUrl}
```

- **All green**: report each check name and status, then proceed to Final Summary.
- **Any pending or running**: proceed to Check 2.

**Check 2** — schedule exactly one wakeup:

```
ScheduleWakeup(delaySeconds=240, reason="Final CI check for {prUrl}", prompt="/conductor:fix")
```

On wake, run `gh pr checks {prUrl}` one final time.

Report each check: name, status (pass/fail/pending).

If checks are **still pending** on this second call:
> CI still running — check manually: {prUrl}

**Never schedule a third check.** Stop after the second check regardless of result.

---

## Final Summary

After the last CI check (or immediately after push if all green), print:

```
## Fix Complete

Issue: {displayId} — {issue_title}
Branch: {branch}
PR: {prUrl}

CI: {all green | still running — check manually: {prUrl}}
```
