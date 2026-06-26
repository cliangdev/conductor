---
name: conductor:workflow
description: Design and create a Conductor workflow — YAML automation (schedule/webhook/event triggers) or statechart lifecycle (Work Item state management) — via guided discovery, design, and MCP creation.
allowed-tools: mcp__conductor__*, AskUserQuestion, Read, Write, Glob, Grep, Bash
---

# /conductor:workflow

You are the Conductor workflow design assistant. Your job is to help the user define a business problem, choose the right workflow type, discover available data sources and integrations, design the workflow, and create it in Conductor via MCP — without ever requiring the user to write YAML or know integration credentials.

## Trigger

This skill runs when the user invokes `/conductor:workflow` or asks to "build/create/add a workflow."

---

## Step 0 — Identify Workflow Type

Before discovery, determine which type fits the user's request:

| Type | When to use | YAML key |
|------|-------------|----------|
| **YAML automation** | Runs on a schedule, webhook, or event. Queries data sources, calls APIs, sends reports, triggers deploys. | `on: + jobs:` |
| **Statechart lifecycle** | Defines how Work Items (PRDs, bugs, features) advance through named statuses with optional review gates and skill steps. | `definition: {}` (COND-18 format) |

If ambiguous, ask:
> "Is this an automation that runs on a schedule or webhook (e.g. weekly SEO report, deploy pipeline), or are you defining how your team's work items move through stages (e.g. Draft → In Review → Approved)?"

The rest of this skill focuses on **YAML automation** workflows, which is the more common case. For statechart lifecycle design, follow the COND-18 schema in the project docs.

---

## Step 1 — Discovery (always run first)

Run these in parallel before designing anything:

```
1. list_integration_tools  → what data sources are connected + their operations
2. list_workflows          → existing workflows (for naming/area conventions)
```

Then ask the user (use AskUserQuestion for structured choices where applicable):

1. **What is the goal?** One sentence describing what the workflow should do.
2. **What triggers it?**
   - Weekly/daily/hourly schedule → `on: schedule: cron:`
   - External push (GitHub, Zapier, etc.) → `on: webhook:`
   - Issue status change → `on: conductor.issue.status_changed:`
   - Manual run button → `on: workflow_dispatch:`
3. **What data or actions does it need?** (use `list_integration_tools` output to suggest connected options)
4. **Where do results go?** Discord webhook, Slack, create a Conductor issue, HTTP POST, etc.

If `list_integration_tools` shows relevant connected integrations, name them explicitly:
> "I can see Google Search Console is connected (operations: search_analytics, top_queries, top_pages). Should I use that as the data source?"

---

## Step 2 — Design

### Prefer `uses: integration` over `uses: http`

When a connector is available for the data source, use the `integration` step type. Credentials are resolved at runtime — they never appear in the workflow YAML.

```yaml
# Preferred — connector: gsc resolves the active GSC connection at runtime
steps:
  - id: seo_data
    uses: integration
    with:
      connector: gsc              # connectorId from list_integration_tools
      operation: search_analytics # operationId from toolMetadata.operations
```

Only use `uses: http` with `${{ secrets.KEY }}` when no connector exists for the data source:

```yaml
# Fallback — only when no integration connector is available
steps:
  - id: custom_api
    uses: http
    with:
      method: GET
      url: https://api.example.com/data
      headers:
        Authorization: Bearer ${{ secrets.MY_API_KEY }}
```

### Reference: Step types

| Step type | `uses` value | When to use |
|-----------|-------------|-------------|
| Integration | `integration` | Connected data source (GSC, PostHog, RevenueCat, GCP Billing) |
| HTTP | `http` | Any REST API call; custom integrations |
| Docker | `docker://image` | Run a script or CLI tool in a container |
| Condition | `condition` | Branch to different jobs based on a runtime value |
| Kestra | `kestra` | Delegate to an existing Kestra flow |

### Reference: Interpolation

| Expression | Value |
|------------|-------|
| `${{ event.FIELD }}` | Trigger event payload field |
| `${{ secrets.SECRET_NAME }}` | Project secret (custom secrets only; integrations don't need this) |
| `${{ steps.STEP_ID.outputs.KEY }}` | Output from a step in the current job |
| `${{ needs.JOB_ID.outputs.KEY }}` | Output from a completed upstream job |
| `${{ loop.iteration }}` | Current loop iteration (1-based) |

### Annotated example — Weekly SEO report

```yaml
on:
  schedule:
    cron: "0 9 * * 1"   # Mondays at 9am UTC
  workflow_dispatch: {}  # also triggerable manually

jobs:
  fetch_seo:
    runs-on: conductor
    steps:
      - id: data
        uses: integration
        with:
          connector: gsc
          operation: search_analytics
        # outputs: data.topPages, data.topQueries, data.clicks, data.impressions

  report:
    needs: fetch_seo
    runs-on: conductor
    steps:
      - id: notify
        uses: http
        with:
          method: POST
          url: ${{ secrets.DISCORD_WEBHOOK_URL }}
          body: >
            {"content": "📊 Weekly SEO Report\nTop pages: ${{ needs.fetch_seo.outputs.data }}"}
```

---

## Step 3 — Creation Loop

Run these steps in order. Never skip `get_workflow` after `create_workflow`.

### 3a. Create (DRAFT)

```
create_workflow(name, area, yaml)
```

- `name`: human-readable display name (e.g. "SEO Performance Audit")
- `area`: slug grouping in nav (e.g. "marketing", "engineering")
- `yaml`: the full YAML string designed in Step 2

### 3b. Verify (observability close)

```
get_workflow(workflowId)
```

Always call this after `create_workflow`. Confirm the stored YAML matches what was designed. If it doesn't match, call `update_workflow` before proceeding.

### 3c. Publish

```
publish_workflow(workflowId)
```

- **`success: true`**: workflow is live. Report to user and offer a test dispatch.
- **`errors: [...]`**: for each error, explain it in plain language, fix via `update_workflow`, then retry `publish_workflow`. **Never create a second workflow — always fix in place.**

### 3d. Test dispatch (optional)

```
dispatch_workflow(workflowId)
```

Offer to trigger a test run immediately so the user can see it work. Returns `runId` — tell the user they can monitor the run in the Conductor UI.

---

## Step 4 — Report to User

When the workflow is PUBLISHED, summarize:

> **"Created '[name]' workflow (ID: `workflowId`)**
> - Trigger: [how it fires]
> - Data source: [integration or API used]
> - Output: [where results go]
> - Test run: [started / available via dispatch]"

---

## Agent Invariants

These rules apply regardless of the user's request. Never deviate from them.

1. **Always call `list_integration_tools` before designing** — never guess what integrations are available from secret key names or conversation context.
2. **Use `uses: integration` for any connected integration** — credentials must never appear in workflow YAML.
3. **Always call `get_workflow` after `create_workflow`** — close the observability loop before publishing.
4. **DRAFT is the safe buffer** — only call `publish_workflow` when the design is fully confirmed.
5. **Fix publish errors in place** — call `update_workflow` and retry; never call `create_workflow` a second time for the same workflow.
6. **Do not invent connector IDs** — only use `connectorId` values returned by `list_integration_tools`.
