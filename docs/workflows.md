# Workflows

Workflows let you automate work that happens around your Conductor project — running scripts, calling APIs, triggering deploys, or orchestrating multi-step pipelines — in response to events like issue status changes, webhooks, cron schedules, or manual triggers.

## Table of contents

- [How workflows work](#how-workflows-work)
  - [Workflow file format](#workflow-file-format)
  - [Triggers](#triggers)
  - [Jobs](#jobs)
  - [Steps](#steps)
  - [Step types](#step-types)
  - [Outputs and interpolation](#outputs-and-interpolation)
  - [Artifacts](#artifacts)
  - [Loops](#loops)
  - [Conditions](#conditions)
  - [Example: monthly SEO analysis](#example-monthly-seo-analysis)
  - [Example: PR code review](#example-pr-code-review)
- [Execution modes](#execution-modes)
  - [Conductor-hosted](#conductor-hosted)
  - [Self-hosted](#self-hosted)
  - [Cloud Run](#cloud-run)
    - [Launch reconciliation](#launch-reconciliation)
  - [Runtime targets (bring your own Cloud Run)](#runtime-targets-bring-your-own-cloud-run)
- [Queued and waiting work](#queued-and-waiting-work)
- [Cancelling a run](#cancelling-a-run)
- [Auto-pause on repeated failures](#auto-pause-on-repeated-failures)
- [Failure notifications](#failure-notifications)
- [System-managed workflows](#system-managed-workflows)
- [Self-hosted setup](#self-hosted-setup)
  - [Prerequisites](#prerequisites)
  - [Running the daemon](#running-the-daemon)
  - [Subscription auth for claude-code steps](#subscription-auth-for-claude-code-steps)
  - [Runner image](#runner-image)
  - [Concurrency and capacity](#concurrency-and-capacity)
  - [Configuration reference](#configuration-reference)

---

## How workflows work

```mermaid
flowchart LR
    subgraph Triggers["on: (triggers)"]
        Manual["workflow_dispatch"]
        Webhook["webhook"]
        Status["Work Item<br/>status change"]
        Cron["schedule (cron)"]
    end

    Run["Workflow run"]
    Jobs["Jobs<br/>(needs · loops · conditions · artifacts)"]
    Steps["Steps<br/>http · docker · kestra · integration<br/>condition · agent · claude-code · action"]

    subgraph Exec["Execution backend"]
        Hosted["Conductor-hosted<br/>Cloud Run"]
        BYO["Your Cloud Run<br/>(runs-on: runtime target)"]
        Self["Self-hosted<br/>conductor-worker + Docker"]
    end

    Manual & Webhook & Status & Cron --> Run --> Jobs --> Steps --> Exec
```

### Workflow file format

Workflows are defined in YAML. Every workflow has two required top-level keys: `on` (triggers) and `jobs`.

```yaml
on:
  workflow_dispatch: {}        # can be triggered manually from the UI

jobs:
  greet:
    steps:
      - name: say hello
        type: http
        method: POST
        url: https://hooks.example.com/notify
        body: '{"text": "Hello from Conductor!"}'
```

An optional `concurrency` key can be set to `"single"` to ensure only one run of the workflow is active at a time:

```yaml
concurrency: single
```

Enforced for scheduled runs (a due cron tick is skipped, not queued, while a run is already active — see
[Cron schedule](#cron-schedule)) and for manual dispatch (`POST .../dispatch` rejects with 409 while a run
is already active, rather than letting a second run silently race the first). Neither case queues the
skipped/rejected attempt for later — see [Queued and waiting work](#queued-and-waiting-work) for what
`concurrency: single` does and doesn't do, and where a skipped schedule tick shows up. **Not** enforced
for a workflow fired programmatically via `fireTrigger` outside those two paths — e.g.
`knowledge-librarian`'s dispatches from `LibrarianDispatchService`, which intentionally run one per domain
lane in parallel (see [System-managed workflows](#system-managed-workflows)) and serialize within a lane
through their own mechanism instead of this flag.

---

### Triggers

The `on` block defines what starts the workflow. Multiple triggers can be combined.

#### Manual dispatch

Adds a **Run Now** button in the workflow UI.

```yaml
on:
  workflow_dispatch: {}
```

Optionally declare named inputs the caller must/may supply. Each is a `name: {description?, required?}`
entry, GitHub-Actions-style:

```yaml
on:
  workflow_dispatch:
    inputs:
      environment:
        description: Target environment
        required: true
      dry_run:
        description: Skip the deploy step
        required: false
```

A manual run passes values in the dispatch request body's `inputs` map (also how `dispatch_workflow`
and the **Run Now** UI pass them):

```json
{ "inputs": { "environment": "production", "dry_run": "false" } }
```

Reference them anywhere with `${{ inputs.environment }}`. `inputs:` is declarative only — it isn't
enforced at dispatch time (an omitted or extra key doesn't fail the run); referencing an
undeclared input produces a publish-time lint warning (see [Outputs and interpolation](#outputs-and-interpolation)).

A workflow that's fired programmatically (e.g. by another service, not a human) can still declare
`workflow_dispatch: {}` to get a recognized trigger kind, while opting out of the **Run Now** button
and the `POST .../dispatch` endpoint/`dispatch_workflow` MCP tool with `manual: false`:

```yaml
on:
  workflow_dispatch:
    manual: false
```

Use this when a step's `${{ event.* }}` references are populated by the code that dispatches the
run, not by a human — a manual click can't supply that data and would only produce a confusing
failure. Defaults to `true` (dispatchable) when omitted, so `inputs:`-only workflows are unaffected.

#### Webhook

A unique webhook URL is generated per workflow. POST to it from any external service (GitHub Actions, Zapier, etc.) to trigger a run. The request body is available as `${{ event.* }}` during the run — the same `${{ event.FIELD }}` expression every trigger type populates (see [Outputs and interpolation](#outputs-and-interpolation)), not something specific to webhooks.

```yaml
on:
  webhook: {}
```

#### Work Item status change

Fires when any Work Item in the project changes status. Use `filters.status` to narrow it to a specific target status.

```yaml
on:
  conductor.work_item.status_changed:
    filters:
      status: "IN_REVIEW"     # only fire when a Work Item moves to IN_REVIEW
```

Available event fields: `event.toStatus`, `event.fromStatus`, `event.workItemId`.

#### GitHub pull request

Fires when a GitHub App-connected repo (the same installation already used for the existing `closes
conductor/KEY-N` merge-to-Work-Item behavior) receives one of four pull request actions: `opened`,
`labeled`, `synchronize`, `reopened`. A merged PR (`closed` with `merged: true`) never fires this
trigger — it goes through the pre-existing issue-completion path instead; a `closed`-without-merge PR
fires neither path.

```yaml
on:
  github.pull_request:
    filters:
      actions: [labeled]           # optional; unfiltered = any of the four actions above
      labels: [code_review_ready]  # optional; unfiltered = any label or no label at all
```

`filters.actions` narrows to specific PR actions. `filters.labels` narrows to specific label names —
only a `labeled` action carries a `label` at all, so a declared label filter excludes every other
action unless `filters.actions` separately admits it.

Available event fields: `event.repoName`, `event.repoFullName`, `event.prNumber`, `event.prTitle`,
`event.author`, `event.headRef`, `event.baseRef`, `event.htmlUrl`, `event.installationId`,
`event.action`, `event.label`. All but `event.action` are best-effort (populated only when present in
the GitHub payload); `event.label` is further only ever populated on a `labeled` action.

#### Cron schedule

Runs on a recurring schedule using standard 5-field cron syntax.

```yaml
on:
  schedule:
    cron: "0 9 * * 1"         # every Monday at 9 AM UTC
```

| Field | Values |
|-------|--------|
| Minute | 0–59 |
| Hour | 0–23 |
| Day of month | 1–31 |
| Month | 1–12 |
| Day of week | 0–7 (0 and 7 = Sunday) |

Combine with `concurrency: single` to skip a scheduled run if a previous one is still running.

---

### Jobs

Jobs are the building blocks of a workflow. Each job runs independently and can depend on other jobs finishing first.

```yaml
jobs:
  fetch-data:
    steps: [ ... ]

  process-data:
    needs: fetch-data          # waits for fetch-data to succeed
    steps: [ ... ]

  notify:
    needs: [fetch-data, process-data]   # waits for both
    steps: [ ... ]
```

#### Job fields

| Field | Description |
|-------|-------------|
| `needs` | Job ID or list of job IDs this job depends on. The job runs only after all listed jobs succeed. |
| `runs-on` | Execution mode: `conductor` (default), `self-hosted`, `cloud-run`, or the name of a project [runtime target](#runtime-targets-bring-your-own-cloud-run). See [Execution modes](#execution-modes). |
| `if` | Expression evaluated before the job starts. If false, the job is skipped. Defaults to `success()` when omitted — see [Conditions](#conditions). |
| `steps` | List of steps to execute in order. |
| `loop` | Repeat this job up to `max_iterations` times until a condition is met. See [Loops](#loops). |

Every job has an implicit condition of `success()` unless it declares its own `if:` — see
[Conditions](#conditions) for exactly how a dependent job reacts when an upstream job fails, is
skipped, or is itself conditional on failure (`if: failure()`/`if: always()`).

---

### Steps

Steps are the individual units of work within a job. They run in order, and a failing step stops the job immediately.

```yaml
steps:
  - id: check-status           # optional; required if you want to reference this step's outputs
    name: Check API status
    type: http
    url: https://api.example.com/health
    outputs:
      healthy: body.status     # extract "status" field from the response body
```

#### Common step fields

| Field | Description |
|-------|-------------|
| `id` | Identifier used to reference this step's outputs as `${{ steps.ID.outputs.KEY }}`. |
| `name` | Human-readable label shown in the run detail UI. |
| `type` | Step type: `http`, `docker`, `kestra`, or `condition`. Defaults to `http`. |
| `if` | Expression evaluated before the step runs. If false, the step is skipped. |

---

### Step types

The field-by-field reference below is also available as data at runtime — `GET
/projects/{projectId}/workflows/step-schema` returns every step type's fields (required-ness, type,
constraints) plus the valid interpolation roots and condition functions, hand-authored from
`WorkflowValidator`'s actual checks (`StepSchemaRegistry`, kept honest by a contract test,
`StepSchemaSyncTest`, that runs generated fixtures through the real validator). Docs and the Claude
Code workflow-authoring skill can read this instead of re-transcribing the rules as prose.

#### `http` — Call an API

Sends an HTTP request and optionally extracts values from the response.

```yaml
- id: get-pr-status
  type: http
  method: GET
  url: https://api.github.com/repos/myorg/myrepo/pulls/42
  headers:
    Authorization: Bearer ${{ secrets.GITHUB_TOKEN }}
  timeout: 30
  outputs:
    state: body.state          # extracts response.state into outputs.state
    mergeable: body.mergeable
```

| Field | Default | Description |
|-------|---------|-------------|
| `url` | — | Request URL (required). |
| `method` | `GET` | HTTP method. |
| `headers` | — | Key-value map of request headers. Values are interpolated. |
| `body` | — | Request body string. Interpolated before sending. |
| `timeout` | `30` | Timeout in seconds (max 120). |
| `outputs` | — | Map of output key → dot-notation path into the response JSON body. |

A response with status code ≥ 400 fails the step.

#### `docker` — Run a container

Executes a command inside a Docker container. The container has access to a shared workspace volume and can write output files that become step outputs.

```yaml
- id: run-tests
  uses: docker://node:20-alpine
  env:
    CI: "true"
    API_KEY: ${{ secrets.DEPLOY_KEY }}
  run: |
    npm ci
    npm test
```

The `uses` field specifies the Docker image. Use `docker://` (no image name) to use the [default Conductor runner image](#runner-image), which includes Node.js, Python, Docker CLI, GitHub CLI, and Claude CLI.

**Workspace:** The container's working directory is `/conductor/workspace`, which persists across steps within the same job.

**Outputs:** Write files to `/conductor/outputs/` to expose values to downstream steps and jobs. Each file becomes an output keyed by its filename.

```bash
# In your run script:
echo "v1.2.3" > /conductor/outputs/version
echo "42"     > /conductor/outputs/build_id
```

Then reference them as `${{ steps.run-tests.outputs.version }}` in later steps.

Docker steps require either `runs-on: conductor` (Conductor-managed infrastructure) or `runs-on: self-hosted` (your own VM). See [Execution modes](#execution-modes).

| Field | Default | Description |
|-------|---------|-------------|
| `timeout_minutes` | `5` | How long to wait for the container to finish before the step is marked failed (max `120`). |

#### `kestra` — Delegate to a Kestra flow

Triggers a flow in your Kestra instance and optionally waits for it to finish.

```yaml
- id: run-etl
  type: kestra
  namespace: myorg.data
  flow_id: nightly-etl
  base_url: ${{ secrets.KESTRA_BASE_URL }}
  api_token: ${{ secrets.KESTRA_API_TOKEN }}
  inputs:
    date: ${{ event.date }}
    env: production
  wait: true
  timeout_minutes: 120
  outputs:
    rows_processed: outputs.rowCount
```

| Field | Default | Description |
|-------|---------|-------------|
| `namespace` | — | Kestra flow namespace (required). |
| `flow_id` | — | Kestra flow ID (required). |
| `base_url` | `KESTRA_BASE_URL` env var, else the conductor-hosted default | Kestra instance URL. Interpolated — set per-project with `${{ secrets.KESTRA_BASE_URL }}` instead of relying on deployment-wide env vars. |
| `api_token` | `KESTRA_API_TOKEN` env var | Kestra API bearer token. Interpolated — `${{ secrets.KESTRA_API_TOKEN }}` keeps it out of the deployment config. |
| `inputs` | — | Input values passed to the Kestra flow. Interpolated. |
| `wait` | `true` | Wait for the flow to complete before continuing. |
| `timeout_minutes` | `60` | How long to wait before timing out. |
| `fail_on_warning` | `false` | Treat Kestra WARNING execution state as a failure. |
| `outputs` | — | Map of output key → dot-notation path into the Kestra execution response. |

Resolution order for `base_url`/`api_token`: the step's own config (interpolated, so
`${{ secrets.* }}` works) → the deployment's `KESTRA_BASE_URL`/`KESTRA_API_TOKEN` env vars → the
conductor-hosted default base URL (no default API token). This lets each workflow target its own
Kestra instance via project secrets, instead of every workflow sharing one deployment-wide instance.

#### `integration` — Query a connected integration

Fetches data from a connected integration (Google Search Console, PostHog, RevenueCat, GCP Billing, etc.) without embedding credentials in the workflow YAML. The active connection is resolved at runtime using the project's linked integration.

```yaml
- id: seo_data
  uses: integration
  with:
    connector: gsc              # connectorId — see Settings → Integrations
    operation: search_analytics # optional; defaults to the connector's main fetch
    params: {}                  # optional connector-specific overrides
```

| Field | Description |
|-------|-------------|
| `connector` | Connector ID of an ACTIVE integration (e.g. `gsc`, `posthog`, `revenuecat`, `gcp-billing`). |
| `operation` | Named operation to run. Omit to use the connector's default fetch. |
| `params` | Optional map of connector-specific override parameters. |

Step outputs are accessible via `${{ steps.ID.outputs.* }}`. The exact output keys depend on the connector and operation — discover them via the `list_integration_tools` MCP tool or by running a test dispatch.

Every integration step also exposes a **`health`** output (`HEALTHY` | `DEGRADED`) — a `DEGRADED` fetch does not fail the step (it may serve stale cached data), and the step log records the reason and the age of the data being served. Gate downstream work on it when stale data would be worse than no data:

```yaml
analyze:
  needs: collect
  if: "${{ needs.collect.outputs.health == 'HEALTHY' }}"
```

**Credentials are resolved at runtime** — they never appear in the workflow YAML. The integration must be connected first via **Settings → Integrations** before it can be referenced in a workflow.

**Available connectors:**

| Connector ID | Name | Operations |
|---|---|---|
| `gsc` | Google Search Console | `search_analytics`, `top_queries`, `top_pages` |
| `posthog` | PostHog | `pageview_trend`, `total_pageviews` |
| `revenuecat` | RevenueCat | `mrr`, `subscriptions`, `trial_conversion` |
| `gcp-billing` | GCP Billing | `cost_by_service` |

#### `condition` — Branch execution

Routes to one of two jobs based on a boolean expression. The condition step itself always succeeds; it just decides which branch to activate next. **A condition step must be the last step in its job.**

```yaml
jobs:
  check-env:
    steps:
      - name: route by environment
        type: condition
        expression: "${{ event.env == 'production' }}"
        then: deploy-prod
        else: deploy-staging

  deploy-prod:
    steps: [ ... ]

  deploy-staging:
    steps: [ ... ]
```

When the condition is true, `then` job is enqueued and `else` job is skipped (and vice versa). Both jobs must be defined in the same workflow and cannot create circular dependencies.

#### `agent` — Run an AI agent

Hands a task to a project-scoped **AI agent** (a named persona configured under **Automation → Agents**) and exposes its answer as step outputs. The agent runs a tool-calling loop against its configured model provider; the provider API key is resolved at runtime and never appears in the workflow YAML.

```yaml
- id: analyze
  uses: agent
  with:
    agent: marketing-agent        # Agent slug (or id) in this project
    task: |
      Analyze landing-page SEO health from the collected data and produce a
      report with prioritized, specific action items.
    context:                      # structured data handed to the agent (interpolated)
      gsc: ${{ needs.collect.outputs.gsc_data }}
      posthog: ${{ needs.collect.outputs.posthog_data }}
    output_schema:                # optional; requests a structured JSON answer
      report: string
      action_items: [string]
  outputs:
    report: body.report           # same dot-path extraction as http
    action_items: body.action_items
```

| Field | Description |
|-------|-------------|
| `agent` | Slug (or id) of an agent defined in this project (required). Interpolated, same as `task`/`context` — e.g. `agent: ${{ event.agentSlug }}` picks the agent per dispatch (used by the Knowledge Center's [domain-aware routing](knowledge.md#domains) to resolve a specialist agent or fall back to a generalist). A literal slug with no `${{ }}` passes through unchanged. Blank after interpolation fails the step. |
| `task` | The instruction for the agent. Interpolated — may reference `${{ steps.* }}` / `${{ needs.* }}`. |
| `context` | Optional map of structured data passed to the agent. Each value is interpolated; upstream integration outputs (JSON strings) embed as-is. |
| `output_schema` | Optional shape that requests a structured JSON answer from the agent. |

The step exposes these outputs:

| Output | Description |
|--------|-------------|
| `text` | The agent's final answer (always present). |
| `data` | The structured JSON answer serialized to a string (present when `output_schema` is set and the agent returns JSON). |
| *each structured field* | Every top-level field of the structured JSON is also exposed as its own output key. |

Declared `outputs:` dot-paths (`body.<field>`) extract from the structured answer just like the `http` step — `body.text` and `body.data` are also available.

The agent must be created first under **Automation → Agents** (persona, model provider, tool bindings) and a provider credential configured for the project (**Settings → AI Providers**). A run that ends in any non-`SUCCEEDED` state fails the step.

##### Runtimes

An agent's *definition* (system prompt, tools, guardrails) is decoupled from the *runtime* that executes it — the workflow step never declares a runtime; it's resolved fresh on every run:

1. The agent's `runtime` config key, if set (`Automation → Agents` → the agent's config, or via `POST/PATCH` on the agent) — `"api"` or `"claude-code"`.
2. Otherwise auto-detected from the project's credentials: a **Claude Code (subscription)** credential (**Settings → AI Providers**) wins over an API key when both are configured, since it gets the full Claude Code tool-calling loop rather than just a single-model ReAct loop.
3. If neither credential is configured, the step fails with a message naming both options.

The Knowledge Center's librarian and domain-specialist agents (seeded by `KnowledgeWorkflowProvisioner`/`KnowledgeDomainService`) are always seeded with an explicit `claude-code` pin rather than left to auto-detection, so a project with an API key credential but no Claude Code subscription credential never silently runs them on a different runtime than they were built for. Since the pin is unconditional (checked before any credential lookup), a project with no Claude Code subscription credential configured at all will now see the librarian/specialist step fail loudly with `CLAUDE_SUBSCRIPTION_NOT_CONFIGURED` (fix under **Settings → AI Providers**) instead of silently degrading to the `api` runtime.

| Runtime | What it is | Guardrails |
|---|---|---|
| `api` | The in-process ReAct loop against the agent's model provider (e.g. Anthropic API key under provider `claude`) — same engine `agent: <slug>` always used before runtimes existed. | `maxToolTurns` ↔ the loop's tool-call cap; unset/`null` falls back to a guardrail default of 8 (the loop always needs a concrete bound). `maxTokens`/`temperature`/`model` are **api-only** (no Claude Code equivalent). The step's `timeout_minutes` is **not** applied here — the loop is bounded by `maxToolTurns`, not wall-clock. |
| `claude-code` | A headless Claude Code container (subscription OAuth), same mechanics as a raw [`claude-code`](#claude-code--run-claude-code-headlessly) step. The agent's `systemPrompt` is prepended to the step's `task` as one prompt (the container has no separate system-prompt channel). | `maxToolTurns` ↔ `--max-turns`; unset/`null` omits the flag entirely, so the CLI runs with **no turn cap** (unlike the `api` runtime, there's no default-8 substitution here). The step's `timeout_minutes` bounds the container execution. The agent's `model`/`maxTokens`/`temperature` are ignored. Runs are tracked as workflow step runs only — no `agent_runs` history row is written on this runtime. |

**Tool mapping (claude-code runtime only).** Each of the agent's bound tool ids must map to a Claude Code `--allowedTools` name (typically an MCP tool the Conductor MCP server also exposes) — any bound tool with no such mapping fails the step immediately with `AGENT_TOOL_NOT_AVAILABLE_ON_CLAUDE_CODE: <toolId>` rather than silently running with fewer tools than configured. An agent with no tools runs with no `--allowedTools` restriction.

**`runs-on` interaction.** The `api` runtime ignores `runs-on` entirely (it's an in-process call, not a container). The `claude-code` runtime uses the job's `runs-on` the same way a raw `claude-code` step does — except an agent step's job commonly has no container-capable `runs-on` at all (agent steps predate runtimes and were written assuming Conductor-hosted execution), so if the job's `runs-on` doesn't resolve to a container target, the runtime defaults to the builtin `cloud-run` target rather than requiring every agent-step job to add `runs-on: cloud-run` just to pick this runtime.

**`credentials:`/`env:` (claude-code runtime only).** An `agent` step also accepts the `claude-code` step's [`credentials:`/`env:`](#claude-code--run-claude-code-headlessly) fields — same shape, same resolution — but only the `claude-code` runtime has a container to inject them into. On the `api` runtime, declaring either fails the step immediately with `CREDENTIALS_NOT_AVAILABLE_ON_API_RUNTIME: agent=<ref> declares credentials/env, but the 'api' runtime has no container to inject them into`.

**Failure modes (`api` runtime).** Anything else escaping the in-process ReAct loop is classified into one of these `errorReason` codes rather than surfacing the raw exception message:

| errorReason | Meaning |
|-------------|---------|
| `CLAUDE_CREDENTIAL_ERROR` | The agent's provider credential couldn't be decrypted (same code the `claude-code` runtime uses for its own credential failures). |
| `TRANSIENT_INFRA_ERROR` | A transient database/transaction failure interrupted the run (e.g. a JDBC commit error) — not a problem with the agent or its configuration. Safe to retry. |
| `AGENT_RUN_ERROR` | Anything else — check the step log for the underlying exception message. |

The `claude-code` runtime's failures are classified under the same codes as a raw `claude-code` step — see below.

#### `claude-code` — Run Claude Code headlessly

Hands a prompt to **Claude Code running headlessly** (`claude -p`) inside the Conductor runner image, optionally with tool access to the Conductor MCP server, and exposes its answer as step outputs. Unlike `agent`, this runs the actual Claude Code CLI — full tool-calling agent loop, not just a single model call — so it can read the input files it's given, use `Read`/`Glob`/MCP tools, and write a Conductor document directly.

```yaml
jobs:
  collect:
    runs-on: conductor
    steps:
      - id: gsc
        uses: integration
        with: { connector: gsc, operation: search_analytics }
        outputs: { data: body.data }

  analyze:
    needs: [collect]
    runs-on: cloud-run            # or: self-hosted / a runtime target — all subscription auth
    steps:
      - id: seo
        uses: claude-code
        with:
          prompt: |
            Read /conductor/inputs/gsc.json (last week's Search Console data).
            Analyze trends, then use the Conductor MCP tools to write a
            document titled "Weekly SEO Report" with findings and 3
            prioritized recommendations.
            Return JSON: {"summary": "...", "document_title": "..."}
          inputs: { gsc.json: "${{ needs.collect.outputs.data }}" }
          conductor_mcp: true
          allowed_tools: "Read,Glob,mcp__conductor__scaffold_document,mcp__conductor__record_asset"
          max_turns: 30
          timeout_minutes: 20
          output_schema:
            type: object
            required: [summary]
            properties: { summary: {type: string}, document_title: {type: string} }
        outputs: { summary: body.summary }
```

| Field | Default | Description |
|-------|---------|-------------|
| `prompt` | — | The instruction given to Claude Code (required). Interpolated — may reference `${{ steps.* }}` / `${{ needs.* }}`. |
| `inputs` | — | Map of `filename: content` written to `/conductor/inputs/` before Claude Code starts, so the prompt can tell it to read them. Values are interpolated; each must be a scalar (string/number/boolean), not a nested object. |
| `conductor_mcp` | `false` | When `true`, wires up the Conductor MCP server (`npx @cliangdev/conductor mcp`) so the prompt can call Conductor tools (e.g. `scaffold_document`, `record_asset`). Requires an `allowed_tools` entry for each MCP tool you want it to use. No setup required: on `runs-on: cloud-run` and named runtime targets, the backend mints a short-lived, run-scoped token (a JWT, claim `type: "mcp"`, TTL matching the project's run-token TTL) and injects it as `CONDUCTOR_API_KEY` for the container's MCP server; on `self-hosted`, unchanged, the daemon uses its own locally-configured project API key. Project API keys (**Settings → API Keys**) remain a feature for external automations (CLI, CI, scripts) — they're no longer involved in workflow MCP auth. |
| `allowed_tools` | — | Comma-separated allowlist passed to `--allowedTools` (e.g. `"Read,Glob,mcp__conductor__scaffold_document"`). Omit to use Claude Code's own defaults. |
| `max_turns` | — | Maximum agent turns (positive integer) before Claude Code stops itself, passed to `--max-turns`. |
| `timeout_minutes` | `30` | Hard wall-clock timeout for the whole step (integer, 1–120). Enforced inside the container (SIGTERM, then SIGKILL) — the step fails with `CLAUDE_TIMEOUT` if exceeded. |
| `output_schema` | — | JSON Schema requesting a structured JSON answer, passed to `--json-schema`. |
| `credentials` | — | List of `{connector, as}` entries. Mints a connector-issued runtime credential and injects it into the container's env under the key named by `as`. See below. |
| `env` | — | Plain map of extra env vars for the container. Values are interpolated, same as the `docker` step's `env:`. |

**Unattended execution & permissions.** These steps run fully unattended inside an isolated, single-use container (a Cloud Run Job execution, or the self-hosted daemon's container) — headless `claude -p` mode has no TTY to answer an interactive approval prompt. The entrypoint always passes `--dangerously-skip-permissions`, so Claude Code's own permission-confirmation system is bypassed entirely. The real security boundary is the container (ephemeral, no shared filesystem/state across runs) plus the scoped, short-lived credentials resolved via `credentials:` below — not in-agent tool confirmation. `allowed_tools` is still forwarded to `--allowedTools`, but with permissions bypassed it's no longer an enforced gate — treat it as documentation of intent, not a restriction on what Claude Code can actually do inside the container.

**`credentials:`** resolves each `{connector, as}` entry at runtime, never in the YAML: `connector` names a connected integration (the same `connector:` id used by `integration`/`action` steps, e.g. `github`), and the project's ACTIVE connection for that connector is resolved when the step runs. `as` is the env var name the resolved secret is injected under inside the container. The connector must support the CREDENTIAL capability — currently `github`, minting a GitHub App installation access token repo-scoped to the current run's `${{ event.repoFullName }}` when present, unscoped otherwise — exposed as e.g. `GH_TOKEN`:

```yaml
- uses: claude-code
  with:
    credentials:
      - connector: github
        as: GH_TOKEN
    env:
      SOME_TOKEN: ${{ secrets.MY_PAT }}
    prompt: |
      `gh pr checkout ...`, review the diff, `gh pr comment ...`
```

`gh`/`git` inside the container pick up `GH_TOKEN` automatically via normal env-var auth — the runner image bundles `gh`, and the entrypoint forwards the full container env into the `claude -p` process, so no extra wiring is needed once the env var is set.

By default `github` credential resolution uses the shared, Conductor-managed GitHub App installation for the project. A project can instead bind its own GitHub Personal Access Token (Integrations → GitHub → "Use a Personal Access Token") — useful when the App doesn't have permissions a workflow needs, without waiting on the App's org-wide permission set to change. The same `credentials: [{connector: github, as: GH_TOKEN}]` YAML resolves it — no workflow changes required. While ACTIVE, the bound PAT always takes precedence over the App connection; unbinding it falls back to the App connection automatically. Because a PAT doesn't auto-rotate the way an App installation token does, its expiration (read from GitHub when available, or as supplied at bind time) is shown on the connection so it can be rotated before it lapses.

**`env:`** is a plain map, same shape/interpolation as the `docker` step's `env:` — for a user's own explicit secret (e.g. `${{ secrets.MY_TOKEN }}`) rather than a connector-resolved one.

**Reserved keys.** Any `as`/env key starting with `CONDUCTOR_` or exactly `CLAUDE_CODE_OAUTH_TOKEN` is rejected — a hard error at publish time (the validator), and belt-and-suspenders at execution time too.

**Never logged, never a step output.** The resolved credential value is written straight into the container's env and never appears in step logs, `steps.*.outputs`, or anywhere else persisted.

**Live logs** — the run detail view streams the step's activity while it runs: a container-started line, then one compact line per Claude turn/tool call (`→ tool: Read {...}`, `💬 …`), ending with `✓ done: N turns`. Lines persist on the step, so they're also there after the run. The container can always read its own `/conductor/inputs/` files — no `allowed_tools` entry needed for that.

The step exposes these outputs:

| Output | Description |
|--------|-------------|
| `text` | Claude Code's final result text (always present). |
| `data` | The structured JSON answer serialized to a string (present when `output_schema` is set and Claude Code returns matching JSON). |
| *each structured field* | Every top-level field of the structured JSON is also exposed as its own output key — mirrors the `agent` step's output mapping. |
| `num_turns` | Number of agent turns used, if reported. |
| `session_id` | Claude Code session id, if reported. |

Declared `outputs:` dot-paths (`body.<field>`) extract from the structured answer the same way as the `http`/`agent` steps.

**Failure modes** — a `claude-code` step fails with one of these `errorReason` values. Every code below (plus the `agent` step's `api`-runtime codes above) also has a static explanation/remediation, surfaced read-only as `explanation`/`remediation` on a FAILED step via `get_workflow_run` — derived at read time from the code, never persisted:

| errorReason | Meaning |
|-------------|---------|
| `CLAUDE_AGENT_ERROR` | Claude Code returned a non-timeout, non-auth, non-rate-limit error. |
| `CLAUDE_AUTH_ERROR` | Authentication failed — expired/invalid OAuth token or API key. |
| `CLAUDE_RATE_LIMITED` | The account's usage/rate limit was exhausted. |
| `CLAUDE_TIMEOUT` | The step exceeded `timeout_minutes`. |
| `CLAUDE_CONFIG_ERROR` | Bad step configuration (e.g. invalid `inputs`/`output_schema` JSON, or `claude` failed to launch). |
| `CLAUDE_CREDENTIAL_ERROR` | A declared `credentials:`/`env:` entry couldn't be resolved — no active connection for the named connector, the connector doesn't support CREDENTIAL, or a malformed entry. |
| `CLAUDE_SUBSCRIPTION_NOT_CONFIGURED` | No Claude Code subscription OAuth token is configured for this runtime — self-hosted: the daemon host; cloud-run/runtime targets: the project's Claude Code credential. See "Auth & runtime targets" below. |
| `CLAUDE_LAUNCH_ERROR` | The Cloud Run execution failed to launch, or ended without the container ever reporting a result (e.g. image pull failure, OOM kill) — the target itself resolved fine; something went wrong running on it. |
| `CLOUD_RUN_LAUNCH_UNCONFIRMED` | Cloud Run never acknowledged the RunJob request within the launcher's retry budget (~60s of retried 20s waits), **and** the [launch reconciliation](#launch-reconciliation) search that follows it found no execution belonging to this step within 3 minutes. Still short of proof that nothing started, but no longer the bare guess it used to be. Distinct from `CLAUDE_LAUNCH_ERROR`, which does reflect a confirmed failure. |
| `RUNTIME_TARGET_NOT_FOUND` | `runs-on` names a [runtime target](#runtime-targets-bring-your-own-cloud-run) that no longer exists in the project. |
| `RUNTIME_TARGET_NOT_READY` | The resolved target isn't usable: a named target that exists but isn't `ACTIVE` (fix under **Integrations → Google Cloud** and retry), a project-designated `cloud-run` target in the same state (fix under **Settings → AI Providers → Runtime**), or — on `runs-on: cloud-run` with no designation and a blank builtin `GCP_CLOUDRUN_PROJECT_ID` — no runtime configured at all (this used to surface as an opaque `CLAUDE_LAUNCH_ERROR`; see [Cloud Run](#cloud-run)'s resolution order). |

**Auth & runtime targets** — `claude-code` steps are **subscription auth only, on every runtime**. The containerized Claude Code CLI is the subscription runtime; there is no API-key path for this step type:

- **`runs-on: self-hosted`**: run `claude setup-token` on the daemon host, then `conductor config set-claude-code-oauth-token <token>` to store it in `~/.conductor/config.json`. The token never leaves the machine or transits Conductor's backend — the daemon injects it directly into the container.
- **`runs-on: cloud-run`** and **`runs-on: <runtime-target>`**: run `claude setup-token` and paste the result as the project's **Claude Code (subscription)** credential (**Settings → AI Providers**, KMS-encrypted, write-only — never returned by the API, resolved at runtime, never in the YAML). This is a distinct credential from the `claude` provider the `agent` step uses.

All three are billed against the token owner's Claude Pro/Max plan, not metered API usage. Per Anthropic's guidance, subscription auth is meant for an individual's own automation, not shared/production/metered use — for that, use the **`agent`** step's **`api`** runtime instead (direct API calls against a per-project Anthropic API key), not a containerized `claude-code` step. Note that an `agent` step can *also* run on the `claude-code` runtime (subscription auth, same as this step) when that's what the agent's config or the project's credentials resolve to — see [Runtimes](#agent--run-an-ai-agent) under the `agent` step above.

#### `action` — Call a connector's outbound action

Invokes a named action on a connected integration (post a Discord message, create an issue in a
third-party tracker, …) without embedding credentials in the workflow YAML. Like the `integration`
step, the active connection is resolved at runtime from the project's linked integration — but
`action` is outbound (writes/side-effects) where `integration` is inbound (reads).

```yaml
- id: notify
  uses: action
  with:
    connector: discord             # connectorId — see Settings → Integrations
    action: post_message           # action id the connector declares
    input:
      content: "Deploy finished: ${{ needs.build.outputs.version }}"
```

| Field | Description |
|-------|-------------|
| `connector` | Connector ID of an ACTIVE integration with the ACTION capability (e.g. `discord`) (required). |
| `action` | Action id the connector declares (e.g. `post_message`) (required). |
| `input` | Map passed to the action. String values are interpolated; other values pass through as-is. |

Discover a connector's action ids, and the input keys each expects, via the `list_integration_tools`
MCP tool (or `list_connector_catalog` for connectors not yet connected in this project) — the
connector declares them itself rather than the workflow author hardcoding a shape that can drift.

Outputs are the action's own result keys — e.g. Discord's `post_message` returns `message_id` and
`channel_id`, referenceable as `${{ steps.notify.outputs.message_id }}`. Declare `outputs:` dot-paths
the same way as `http`/`integration` steps to extract nested values.

**Idempotency.** Each invocation is keyed by this job run + step id — re-driving the *same* job run
(e.g. a retry) never double-fires the action; a genuinely new job run always fires it again.

**Failure handling** mirrors the `ActionConnector` SPI's transient-vs-permanent contract:
- **Transient** (the connector threw — network error, 5xx, timeout): retried automatically, up to 3
  attempts inline within the step, then a background sweep continues retrying (exponential backoff)
  up to 5 attempts total before the action is dead-lettered.
- **Permanent** (the connector returned an error result — e.g. a 4xx rejection, bad webhook URL,
  malformed input): dead-lettered immediately, no retry — the provider already told us the request
  itself was invalid, so retrying would just waste attempts.

Either way the step fails if the action never succeeds; the step log records the connector, action
id, and connection used.

**Credentials are resolved at runtime** — they never appear in the workflow YAML. In the example
above, the Discord webhook URL is configured once on the connection (**Settings → Integrations →
Discord**), not pasted into the step.

---

### Outputs and interpolation

Use `${{ ... }}` to inject dynamic values into any string field.

| Expression | Value |
|------------|-------|
| `${{ event.FIELD }}` | Field from the trigger event payload |
| `${{ secrets.SECRET_NAME }}` | Project secret (name must be uppercase with underscores) |
| `${{ inputs.KEY }}` | A manual-dispatch input value (see [Manual dispatch](#manual-dispatch)) |
| `${{ steps.STEP_ID.outputs.KEY }}` | Output from a step in the current job |
| `${{ steps.STEP_ID.result }}` | Terminal result of a prior step in the same job: `success`, `failure`, or `skipped` |
| `${{ needs.JOB_ID.outputs.KEY }}` | Output from a completed upstream job |
| `${{ needs.JOB_ID.result }}` | Terminal result of an upstream job: `success`, `failure`, or `skipped` |
| `${{ needs.JOB_ID.artifacts.NAME }}` | Signed, time-limited download URL for an artifact an upstream job produced — see [Artifacts](#artifacts) |
| `${{ loop.iteration }}` | Current loop iteration number (1-based) |

Unknown references resolve to an empty string rather than erroring.

**`event.FIELD` isn't webhook-only.** Every trigger type stores its payload on the run, and
`${{ event.FIELD }}` reads whatever is there regardless of what started the run: a webhook's POST body, a
`workflow_dispatch` call's inputs, a Work Item status-change event's `toStatus`/`fromStatus`/`workItemId`,
or a payload a system process passed programmatically. The Knowledge Center's `knowledge-librarian`
workflow is an example of the last case — it's dispatched by a scheduler, not a human, and reads
`${{ event.sourceIds }}`/`${{ event.projectId }}`/`${{ event.domain }}` from a payload the dispatcher
built directly (see [`docs/knowledge.md`](knowledge.md)) rather than declaring `workflow_dispatch`
`inputs`. Its `with.agent: ${{ event.agentSlug }}` is the same dispatcher-built payload driving *which*
agent runs the step, not just what it's told — `with.agent` is interpolated exactly like `task`/`context`.

**Output merge rule.** `needs.JOB_ID.outputs.*` is the merge of every step's declared outputs across
that job, in deterministic execution order (start time, then step id as a tiebreak). If two steps in
the same job declare the same output key, the later step (by execution order) wins; the collision is
logged as a warning.

**Publish-time lint.** Saving a workflow does a best-effort scan of every `${{ ... }}` reference and
adds a **warning** (never a hard error — these are hints, not a strict contract) for:
- an unknown root (anything other than `event`, `secrets`, `steps`, `needs`, `inputs`, `loop`)
- `steps.ID.*` naming a step id not present in the same job
- `needs.JOB.*` naming a job not declared in this job's `needs`
- `inputs.KEY` naming a key not declared under `on.workflow_dispatch.inputs`

```yaml
jobs:
  build:
    steps:
      - id: compile
        uses: docker://node:20-alpine
        run: |
          npm ci && npm run build
          echo "1.0.${{ loop.iteration }}" > /conductor/outputs/version

  deploy:
    needs: build
    steps:
      - name: deploy to environment
        type: http
        method: POST
        url: https://deploy.example.com/release
        body: >
          {"version": "${{ needs.build.outputs.version }}",
           "env": "${{ event.environment }}",
           "token": "${{ secrets.DEPLOY_TOKEN }}"}
```

**Condition expressions** (in `if` and `condition` step) support:
- Comparison: `==`, `!=`, `>`, `<`
- Logical: `&&`, `||`
- Bare value (truthy if non-empty and not `"false"`)

```yaml
if: "${{ needs.validate.outputs.passed == 'true' && event.branch == 'main' }}"
```

---

### Artifacts

Outputs (`outputs:`) are small string values extracted into `${{ ... }}` expressions. **Artifacts**
are for passing whole files between jobs — a build binary, a rendered report, a dataset — without
round-tripping them through outputs or a job-shared filesystem.

A `docker` or `claude-code` step declares what it produces with `artifacts:`; a downstream job
declares what it consumes with a job-level `consumes:`:

```yaml
jobs:
  build:
    runs-on: self-hosted           # docker artifacts require self-hosted (see below)
    steps:
      - id: compile
        uses: docker://node:20-alpine
        run: npm ci && npm run build && cp dist/app.tar.gz /conductor/workspace/app.tar.gz
        artifacts:
          - name: app-bundle
            path: app.tar.gz        # workspace-relative

  deploy:
    needs: build
    consumes: [app-bundle]          # must be produced by one of this job's needs
    steps:
      - uses: docker://
        run: |
          # $CONDUCTOR_ARTIFACTS_DIR/app-bundle is the downloaded file
          ./scripts/deploy.sh "$CONDUCTOR_ARTIFACTS_DIR/app-bundle"
```

| Field | Where | Description |
|-------|-------|--------------|
| `artifacts` | step-level (`docker`, `claude-code` only) | List of `{name, path}` this step produces. `name` must match `^[a-z0-9_-]{1,160}$` and be unique across the whole run. `path` is workspace-relative, resolved inside the step's container. |
| `consumes` | job-level | List of artifact names this job needs downloaded before its steps run. Each name must be produced by a step in one of this job's `needs` — checked at save time. |

**Reading a consumed artifact:**
- In a container (`docker` self-hosted, or `claude-code` on any runtime): the daemon/entrypoint
  downloads every consumed artifact into a bind-mounted directory before the step runs, exposed at
  `$CONDUCTOR_ARTIFACTS_DIR` (fixed at `/conductor/artifacts`).
- In any step's `${{ }}` interpolation: `${{ needs.JOB.artifacts.NAME }}` resolves to a signed,
  time-limited (60-minute) GET URL for the file — useful for an `http` step that just needs to pass
  the file along, without downloading it into a container.

**Support matrix.** `docker` steps need `runs-on: self-hosted` to declare artifacts — the
Conductor-hosted worker-VM docker path doesn't implement artifact upload/download (the validator
rejects `artifacts:` on a conductor-hosted `docker` step with a clear error rather than half-working).
`claude-code` steps support artifacts on **any** runtime (`self-hosted`, `cloud-run`, or a runtime
target) — but see the runner-image note below for `cloud-run`/runtime-target support specifically.

**Failure semantics — never silent.** A missing declared file (the step said it would produce
`app-bundle` at that path, but the file isn't there when the step finishes) or a failed
upload/download always fails the job — there's no silent "artifact just wasn't there" path. Look for
`ARTIFACT_MISSING`, `ARTIFACT_UPLOAD_FAILED`, or `ARTIFACT_DOWNLOAD_FAILED` in the failed step's
`errorReason`/log.

**Validator rules enforced at save time:** artifact name shape and uniqueness (across the *entire*
run graph, not just within one job — two jobs can't produce the same name), artifact type restricted
to `docker`/`claude-code` steps, and every `consumes:` entry traced back to a producing step in the
job's own `needs`.

**Daemon version floor.** Self-hosted artifact support requires an up-to-date `conductor` CLI/daemon
— the dispatch protocol gained the artifact fields additively (protocol version unchanged), so an
older daemon simply ignores them rather than erroring, which means a stale daemon will silently skip
downloading/uploading artifacts it doesn't know about. Run `conductor start` from a current install.

**Runner image note (claude-code, cloud-run/runtime targets).** The containerized
`conductor-claude-entrypoint` gained the same artifact-download/upload contract as the daemon, but
that only takes effect once a runner image is rebuilt and pushed with the updated entrypoint — the
currently published `ghcr.io/cliangdev/conductor-runner` tag predates this change. If a `claude-code`
step with `artifacts`/`consumes` on `cloud-run` or a runtime target doesn't pick up files, rebuild and
re-push the image (see [Runner image](#runner-image) / [Choosing an image](#runtime-targets-bring-your-own-cloud-run)) before relying on it in production.

---

### Loops

A loop re-runs a job repeatedly until a condition is met or the maximum number of iterations is reached. This is useful for polling, retrying, or paginating.

```yaml
jobs:
  wait-for-deployment:
    loop:
      max_iterations: 10
      until: "${{ steps.check.outputs.status == 'healthy' }}"
      fail_on_exhausted: false    # mark as LOOP_EXHAUSTED instead of FAILED
    steps:
      - id: check
        type: http
        url: https://api.example.com/health
        outputs:
          status: body.status
```

| Field | Default | Description |
|-------|---------|-------------|
| `max_iterations` | — | Maximum number of times to run the job (required). |
| `until` | — | Expression evaluated after each iteration. Loop stops when true (required). |
| `fail_on_exhausted` | `true` | If true, the run fails when max iterations are reached. If false, the job ends with status `LOOP_EXHAUSTED` and the run continues. |

Use `${{ loop.iteration }}` inside loop steps to track which iteration is running (starts at 1).

Each iteration is shown as a separate sub-row in the run detail view.

---

### Conditions

Use `if` on a job or step to skip it based on a runtime value:

```yaml
jobs:
  deploy:
    needs: build
    if: "${{ needs.build.outputs.tests_passed == 'true' }}"
    steps:
      - name: deploy
        type: http
        url: https://deploy.example.com/ship
```

A skipped job shows in the run history as **SKIPPED**. Every job — whether or not it declares `if:`
— has an effective condition: an explicit `if:` if you write one, otherwise the implicit default
`success()`.

#### `always()`, `success()`, `failure()`

Inside an `if:` (job or step) or a `condition` step's `expression:`, these three functions read the
terminal status of the current job's `needs` (for a job-level `if:`) — the same facts are reused for
every step's `if:` within that job:

| Function | True when |
|----------|-----------|
| `always()` | Always — runs regardless of upstream outcome. |
| `success()` | Every one of the job's `needs` ended `SUCCESS`. False if any need was `FAILED`, `LOOP_EXHAUSTED`, **or `SKIPPED`** — a skip cascades through the default condition the same way a failure does, matching GitHub Actions. A job with no `needs` is vacuously successful (root jobs still run by default). |
| `failure()` | Any one of the job's `needs` ended `FAILED` or `LOOP_EXHAUSTED`. A `SKIPPED` need does **not** trip `failure()` — only a real failure does. |

They're composable with `&&`, `||`, and the usual comparisons, just like any other value:

```yaml
notify:
  needs: [build, test, deploy]
  if: "${{ failure() || needs.deploy.outputs.rollback == 'true' }}"
```

`${{ needs.JOB.result }}` (see [Outputs and interpolation](#outputs-and-interpolation)) gives you the
same information per-job as a plain string (`success`/`failure`/`skipped`) when you want to branch on
one specific upstream job rather than the aggregate.

#### Step-level `continue-on-error`

A step can opt out of failing its job:

```yaml
- id: optional-lint
  type: http
  url: https://lint.example.com/check
  continue-on-error: true
```

A failed step with `continue-on-error: true` does not stop the job or fail it — later steps in the
job still run, and `${{ steps.optional-lint.result }}` is `failure` so a later step can branch on it:

```yaml
- if: "${{ steps.optional-lint.result == 'failure' }}"
  type: http
  url: https://slack.example.com/notify-lint-issue
```

(A `condition` step cannot have `continue-on-error` — routing steps always succeed by definition.)

#### Failure propagation: how a downstream job reacts

**This is a behavior change** from earlier versions of this engine. Previously, a failed job hard-skipped
its *entire* downstream closure — no dependent job's `if:` was ever evaluated once something upstream
failed. Now, `FAILED`/`SKIPPED`/`LOOP_EXHAUSTED` are terminal outcomes exactly like `SUCCESS`: once a
job reaches one of them, every dependent whose `needs` are now all terminal becomes **ready**, and
decides for itself — via its own explicit `if:`, or the implicit `success()` — whether to actually run.

| | Old behavior | New behavior |
|---|---|---|
| Upstream job fails, downstream has no `if:` | Downstream hard-skipped, unconditionally | Downstream becomes ready, evaluates the implicit `success()`, which is false → still skips. **Same observable result for the common case.** |
| Upstream job fails, downstream has `if: failure()` | Never evaluated — hard-skipped anyway, so this `if:` was effectively dead code | Downstream becomes ready, evaluates `failure()` → true → **actually runs**. Cleanup/notify/rollback jobs work as written. |
| Upstream job fails, downstream has `if: always()` | Never evaluated — hard-skipped anyway | Downstream becomes ready, `always()` → true → **actually runs**. |
| Upstream job succeeds | Downstream runs (subject to its own `if:`) | Unchanged. |
| Run-level status when any job failed | `FAILED` | Still `FAILED` — a failed job failing the overall run hasn't changed, only whether dependents get a chance to react. |

Net effect: **a workflow with no `if:` on any job behaves identically to before** (the implicit
`success()` reproduces the old hard-skip for the plain case). The only workflows that behave
differently are ones that declare `if: failure()` or `if: always()` on a job downstream of a
potential failure — those now actually run instead of being silently skipped, which is what most
authors intended when they wrote them.

---

### Example: monthly SEO analysis

A complete pipeline that, on the first of every month, collects search + product analytics, hands them to a marketing agent for analysis, and posts the report to Discord. It chains jobs with `needs`: `collect_gsc` + `collect_posthog` (each an `integration` step) → `analyze` (an `agent` step) → `deliver` (an `http` step). Each connector lives in its own collection job so its `data` output is referenced unambiguously via `needs.<job>.outputs.data`.

```yaml
name: Monthly SEO analysis
on:
  schedule:
    cron: "0 9 1 * *"        # 09:00 UTC on the 1st of each month

jobs:
  collect_gsc:
    steps:
      - id: gsc
        uses: integration
        with:
          connector: gsc
          operation: search_analytics

  collect_posthog:
    steps:
      - id: posthog
        uses: integration
        with:
          connector: posthog
          operation: pageview_trend

  analyze:
    needs: [collect_gsc, collect_posthog]
    steps:
      - id: report
        uses: agent
        with:
          agent: marketing-agent
          task: |
            Analyze landing-page SEO health from the collected Search Console and
            PostHog data. Produce a concise health summary and a prioritized list of
            specific, actionable improvements.
          context:
            gsc: ${{ needs.collect_gsc.outputs.data }}
            posthog: ${{ needs.collect_posthog.outputs.data }}

  deliver:
    needs: analyze
    steps:
      - id: post
        type: http
        method: POST
        url: ${{ secrets.DISCORD_WEBHOOK_URL }}
        headers:
          Content-Type: application/json
        body: |
          { "content": ${{ needs.analyze.outputs.text }} }
```

`needs.collect_gsc.outputs.data` and `needs.collect_posthog.outputs.data` are the JSON blobs each `integration` step emits; they are embedded into the agent's `context`. The agent's final answer is exposed as `needs.analyze.outputs.text`, which the `deliver` job posts to the Discord webhook (stored as a project secret).

---

### Example: PR code review (multi-repo org)

A `github.pull_request` trigger gated to a specific label, fanning out to a per-repo review job, then notifying regardless of outcome. This shape fits an org where backend and frontend live in **separate repos**, so `event.repoFullName` alone tells you which reviewer applies.

```yaml
name: PR Code Review
on:
  github.pull_request:
    filters:
      labels: [code_review_ready]

jobs:
  review-backend:
    if: "${{ event.repoFullName }} == 'org/backend-repo'"
    runs-on: cloud-run
    steps:
      - id: review
        uses: claude-code
        with:
          credentials:
            - connector: github
              as: GH_TOKEN
          allowed_tools: "Bash"
          timeout_minutes: 30
          prompt: |
            You are a senior backend reviewer. Repo: ${{ event.repoFullName }},
            PR #${{ event.prNumber }} ("${{ event.prTitle }}") by @${{ event.author }}.
            `gh pr checkout ${{ event.prNumber }} --repo ${{ event.repoFullName }}`, review the
            diff against `${{ event.baseRef }}`, then post one PR comment tagging the author:
            `gh pr comment ${{ event.prNumber }} --repo ${{ event.repoFullName }} --body "..."`

  review-frontend:
    if: "${{ event.repoFullName }} == 'org/frontend-repo'"
    runs-on: cloud-run
    steps:
      - id: review
        uses: claude-code
        with:
          credentials:
            - connector: github
              as: GH_TOKEN
          allowed_tools: "Bash"
          timeout_minutes: 30
          prompt: |
            You are a senior frontend reviewer. Repo: ${{ event.repoFullName }},
            PR #${{ event.prNumber }} ("${{ event.prTitle }}") by @${{ event.author }}.
            `gh pr checkout ${{ event.prNumber }} --repo ${{ event.repoFullName }}`, review the
            diff against `${{ event.baseRef }}`, then post one PR comment tagging the author.

  notify:
    needs: [review-backend, review-frontend]
    if: always()
    steps:
      - id: notify
        uses: action
        with:
          connector: discord
          action: post_message
          input:
            content: "PR review posted for ${{ event.repoFullName }} #${{ event.prNumber }}: ${{ event.htmlUrl }}"
```

The label gate happens once at trigger time; per-repo routing happens per-job via `if:` so a backend-only PR never spins up the frontend container. `notify` runs regardless of review outcome via `needs` + `always()`. Either reviewer job could equally use a persisted `uses: agent` step (with the same `credentials:` field) instead of an inline `uses: claude-code` prompt — both are supported.

### Example: PR code review (monorepo)

For a **monorepo** (one repo, multiple areas — e.g. this repo's own `conductor-backend/`/`conductor-frontend/`/`conductor-tools/`), don't port the multi-repo shape above by adding a job that detects which paths changed and routes to per-area jobs — that's an extra Cloud Run execution and an extra full `git clone` per area for a decision a single `git diff --stat` line answers. Instead, use **one** job whose agent self-detects the touched areas from the diff and reviews only those, in one pass, posting one comment:

```yaml
jobs:
  review:
    runs-on: cloud-run
    steps:
      - id: review
        uses: agent
        with:
          agent: pr-review-agent
          credentials:
            - connector: github
              as: GH_TOKEN
          timeout_minutes: 30
          task: |
            Review PR #${{ event.prNumber }} in ${{ event.repoFullName }}. Check out the PR, diff it
            against ${{ event.baseRef }}, and apply the checklist for whichever areas the diff touches
            (your system prompt defines the per-area checklists). Post one comment covering every
            touched area.
```

The agent's system prompt carries the per-area checklists (backend conventions, frontend design-system conventions, etc.) and the instruction to skip whatever the diff doesn't touch — that logic belongs in the agent, not in extra workflow jobs. This also avoids each area re-cloning the repo: one checkout, one review pass, one comment.

---

## Execution modes

`runs-on` is a job-level setting with three built-in modes, plus named [runtime targets](#runtime-targets-bring-your-own-cloud-run) for running in your own cloud. `docker` and `claude-code` steps are the ones that use it — a self-hosted job dispatches **all** of its steps to your daemon as a unit, so mix step types across jobs (not within one self-hosted job) if you need both a self-hosted `docker`/`claude-code` step and a Conductor-hosted `http`/`kestra`/`integration`/`agent` step in the same pipeline.

### Conductor-hosted

```yaml
jobs:
  build:
    runs-on: conductor         # default; can be omitted
    steps:
      - uses: docker://node:20-alpine
        run: npm ci && npm test
```

Docker containers run on Conductor's managed infrastructure. No setup required on your end. Suitable for most CI/CD tasks that don't require access to your internal network or private resources.

### Self-hosted

```yaml
jobs:
  deploy-internal:
    runs-on: self-hosted
    steps:
      - uses: docker://
        run: ./scripts/deploy.sh ${{ event.environment }}
```

Docker containers run on a VM you control. Use self-hosted when your workflow needs to:

- Access resources on your private network (databases, internal APIs, on-prem services)
- Use your own secrets or credentials stored locally
- Run in a region or cloud account you manage
- Have greater control over the container environment

Self-hosted jobs require a running **conductor daemon** process on your VM. See [Self-hosted setup](#self-hosted-setup) below.

### Cloud Run

```yaml
jobs:
  analyze:
    runs-on: cloud-run
    steps:
      - id: seo
        uses: claude-code
        with:
          prompt: Summarize the attached data.
```

Currently only meaningful for `claude-code` steps. The step runs as a **Google Cloud Run Job execution**, using subscription auth. No setup required beyond configuring the project's **Claude Code (subscription)** credential under **Settings → AI Providers** — see "Auth & runtime targets" in the `claude-code` step section above.

**Resolution order.** `runs-on: cloud-run` doesn't name one fixed place to run — it resolves per project, checked in order:

1. **A project-designated runtime target** (**Settings → AI Providers → Runtime**) — an admin links one of the project's [runtime targets](#runtime-targets-bring-your-own-cloud-run) as "the" `cloud-run` destination for that project. Must be `ACTIVE` with a live connection; otherwise the step fails with `RUNTIME_TARGET_NOT_READY` carrying the target's own error (e.g. its connection was removed).
2. **The operator's builtin target** — Conductor's own GCP project, configured via the `GCP_CLOUDRUN_*` env vars below — used when the project has no designation.
3. **Neither configured** — no designation *and* a blank builtin project id — fails the step with an actionable `RUNTIME_TARGET_NOT_READY` ("No Claude runtime configured — link a runtime target in Settings → AI Providers → Runtime, or set GCP_CLOUDRUN_PROJECT_ID on the backend") instead of the opaque Cloud Run gRPC error this used to surface as an undiagnosable `CLAUDE_LAUNCH_ERROR`.

This applies to explicit `runs-on: cloud-run` in workflow YAML too — the designation is project-wide, not step-specific. An author who needs a *specific* target regardless of the project's designation should pin it by name (`runs-on: <target-name>`) instead — see [Runtime targets](#runtime-targets-bring-your-own-cloud-run).

The **Settings → AI Providers → Runtime** section (next to the Claude Code credential row) shows the effective runtime for the project — the designated target's status, or whether the builtin fallback is actually configured — and lets an admin change or clear the designation. Provider "Verify" reruns a preflight against whichever target is effective, so a misconfigured runtime (e.g. the incident that prompted this: a blank `GCP_CLOUDRUN_PROJECT_ID` on the deployed backend) shows up as an actionable error there instead of only failing at the next real run.

**One-time infra setup for the builtin target** (operator-only, not per-project): the backend launches executions against a pre-created Cloud Run Job resource rather than creating one per run — image, retry policy, etc. are pinned on that resource:

```bash
gcloud run jobs create conductor-claude-code \
  --image ghcr.io/cliangdev/conductor-runner:3 \
  --command conductor-claude-entrypoint \
  --max-retries 0 \
  --region <region>
```

The backend needs these env vars to target it — all optional once at least one project designates a runtime target instead, but the builtin remains the fallback for every project that hasn't:

| Variable | Description |
|----------|--------------|
| `GCP_CLOUDRUN_PROJECT_ID` | GCP project id hosting the Cloud Run Job. |
| `GCP_CLOUDRUN_REGION` | Region the Job resource was created in. |
| `GCP_CLOUDRUN_CLAUDE_JOB_NAME` | Name of the pre-created Job resource (`conductor-claude-code` above). |

#### Launch reconciliation

Cloud Run's `RunJob` API has no idempotency key, so Conductor can never simply retry a launch it is
unsure about — a blind retry risks a second real container. That makes "did the launch happen?" a
question worth answering precisely.

Starting an execution has two completion points: Cloud Run *acknowledging* the request, and the
resulting `Execution` resource *materializing*. Conductor waits up to 3×20s for the acknowledgement.
Exhausting that budget is genuinely inconclusive — the request can still land afterwards. It has: in
one production case the acknowledgement wait was abandoned and Cloud Run created the execution 41
seconds later, which then ran the job to completion and reported a result nobody was listening for.

So instead of guessing, Conductor looks. Every execution it launches carries a per-step unique
`CONDUCTOR_WORKER_JOB_ID` in its container environment, and Cloud Run echoes container-env overrides
onto the `Execution` it creates. After an unacknowledged launch, Conductor polls `ListExecutions` for
up to 3 minutes looking for that id. Finding it recovers the step outright: the execution name is
persisted and polled to completion exactly as a normal launch would be. Only when nothing turns up
does the step fail with `CLOUD_RUN_LAUNCH_UNCONFIRMED`.

As a last line of defence, a container that reports its own result after its step was already failed
as `CLOUD_RUN_LAUNCH_UNCONFIRMED` has that report **adopted** rather than discarded — that status is
an admission of ignorance, and a report from the container is evidence that outranks it. The step's
result and outputs are corrected; the job and run are left as settled, since re-opening them would
skip the dependent-job propagation a genuine success performs.

### Runtime targets (bring your own Cloud Run)

A **runtime target** is a named, project-owned place jobs can run — your own GCP project instead of Conductor's. Reference it by name:

```yaml
jobs:
  analyze:
    runs-on: marketing-gcp        # a runtime target named "marketing-gcp"
    steps:
      - uses: claude-code
        with: { prompt: ... }
```

`conductor`, `self-hosted`, and `cloud-run` are reserved built-ins; any other `runs-on` scalar must match a runtime target in the project (saving the workflow fails otherwise). The target's name is validated at save time whatever its status; **readiness is enforced at execution time** — a run against a target that is still `PROVISIONING` or in `ERROR` fails the step with `RUNTIME_TARGET_NOT_READY`.

**One-time setup:**

1. **Connect Google Cloud** (Settings → Integrations → Google Cloud): paste or upload a **service-account JSON key**. The key is stored encrypted (same KMS envelope as all integration credentials) and never returned by the API. The service account needs:
   - `roles/run.developer` — create/update/run the Cloud Run Job
   - `roles/iam.serviceAccountUser` on the Job's runtime service account
   - The *runtime* service account (which the Job executes as) needs `roles/artifactregistry.reader` to pull your image, plus whatever the workload itself uses.
2. **Create the runtime target** (Integrations → Google Cloud → Runtime targets): name (slug), the gcp connection, GCP project id, region, and the **container image** to pin on the Job (e.g. `us-central1-docker.pkg.dev/PROJECT/conductor/runner:3`). Job name defaults to `conductor-<name>`.

Creating (or editing-with-a-changed-value) a target provisions it synchronously: Conductor **verifies the image exists** in your Artifact Registry, then **creates or updates the Cloud Run Job** in your project (image + `conductor-claude-entrypoint` command pinned on the Job, `--max-retries 0`). The target lands `ACTIVE`, or `ERROR` with the reason on the row (missing image, missing IAM permission, …) — fix and hit *Retry provisioning*. A warning (not an error) is recorded when the image's runner-contract label can't be verified.

**Image tags are pinned, not floating.** Cloud Run resolves an image *tag* (e.g. `:latest`) to a specific *digest* at the moment Conductor calls the create/update-Job API — not at each execution. Pushing a newer image to that same tag afterward has no effect on an already-provisioned target; the Job keeps running whatever digest was current at the last provision. Re-saving the edit form with the *same* image string is also a no-op (nothing changed, so nothing re-provisions). To pick up a new push without changing the tag, use **Sync to latest image** from an `ACTIVE` target's row menu (Integrations → Google Cloud → Runtime targets) — it re-runs the same verify-image + update-Job call the backend already uses for `ERROR`-state retries, just exposed for `ACTIVE` targets too.

Every successful provision records what GCP actually pinned — shown under the row as *"Synced \<relative time\> · \<short digest\>"* (e.g. `Synced 5m ago · sha256:abcdef01…`, hover for the full image reference). This is the one place to check "is this target actually running what I just pushed" without reaching for `gcloud run jobs describe` — the `image` field elsewhere on the row is only the configured tag, which never changes on its own.

Deleting a target removes only Conductor's record — **the Cloud Run Job in your project is left in place**.

**Choosing an image.** The image must honor the Conductor runner contract (the `conductor-claude-entrypoint` self-reporting entrypoint — see [Runner image](#runner-image)). For claude-code-only targets, prefer the **dedicated claude runner** (`runner-image/Dockerfile.claude-runner`): node-slim + pinned Claude CLI + pre-warmed MCP resolution, with `DISABLE_AUTOUPDATER=1`, plus `git`/`gh`/`curl`/`jq` so a step's Bash tool can act on `credentials:`-issued tokens (checkout a PR, call an API) regardless of project type — it deliberately omits Python and the Docker CLI (only `docker` steps need those), so it's substantially smaller and cold-starts faster than the general-purpose image. Patterns:

- **Mirror the dedicated claude runner** into your Artifact Registry — published to `ghcr.io/cliangdev/conductor-claude-runner`, rebuilt automatically on every change to `runner-image/Dockerfile.claude-runner` (see [Runner image](#runner-image)):
  ```bash
  docker pull ghcr.io/cliangdev/conductor-claude-runner:latest
  docker tag ghcr.io/cliangdev/conductor-claude-runner:latest us-central1-docker.pkg.dev/PROJECT/conductor/claude-runner:latest
  docker push us-central1-docker.pkg.dev/PROJECT/conductor/claude-runner:latest
  ```
  Prefer an immutable `sha-<commit>` tag over `:latest` if you want provisioning to be a deliberate, reviewable step rather than picking up whatever's newest; either way, a later pull needs **Sync to latest image** (see above) to actually take effect on an existing target.
- **Or build it yourself** from the repo instead of mirroring:
  ```bash
  docker build --platform linux/amd64 -f runner-image/Dockerfile.claude-runner \
    -t us-central1-docker.pkg.dev/PROJECT/conductor/claude-runner:1 runner-image/
  docker push us-central1-docker.pkg.dev/PROJECT/conductor/claude-runner:1
  ```
- **Mirror the general-purpose image** into your Artifact Registry (works for claude-code too, just bigger):
  ```bash
  docker pull ghcr.io/cliangdev/conductor-runner:3
  docker tag ghcr.io/cliangdev/conductor-runner:3 us-central1-docker.pkg.dev/PROJECT/conductor/runner:3
  docker push us-central1-docker.pkg.dev/PROJECT/conductor/runner:3
  ```
- **Derive a custom image** that bakes your methodology in as a Claude Code skill (plus any extra tooling), keeping the task prompt in the workflow YAML thin — base it on either runner image:
  ```dockerfile
  FROM us-central1-docker.pkg.dev/PROJECT/conductor/claude-runner:1
  COPY skills/seo-report/ /home/runner/.claude/skills/seo-report/
  ```
  Build and push it from your own CI. The base image already contains the Claude CLI and the entrypoint — don't override `ENTRYPOINT`/`CMD`, don't switch off the non-root `runner` user, and leave `/conductor/{workspace,inputs,outputs}` alone. A step prompt can then just say *"Use the seo-report skill on the inputs in /conductor/inputs/"* (add `Skill` to `allowed_tools`).

Credential-wise a runtime-target job behaves like `cloud-run`: the project's Claude Code subscription token, and — when `conductor_mcp: true` — a freshly-minted run-scoped MCP token, are injected by the backend; compute runs — and is billed — in your GCP project.

---

## Queued and waiting work

Most of the time, `PENDING` isn't a queue you need to think about. Every job dispatch first inserts a
row into the engine's internal job queue (`workflow_job_queue`), then reaches the engine one of two
ways depending on `conductor.workflow.job-executor.dispatch-mode`:

- **`poll`** (default): a `@Scheduled` tick drains `workflow_job_queue` directly, on a base interval
  (`conductor.workflow.job-executor.poll-interval-ms`, 500ms by default) with adaptive backoff (idle
  ticks back off to as slow as 10× the base interval).
- **`cloud-tasks`**: each enqueue also pushes a Cloud Task that calls the engine back over HTTP
  (`POST /internal/v1/workflow-runs/{runId}/jobs/{jobId}/dispatch`), so dispatch arrives as a genuine
  inbound request rather than depending on an always-warm background thread — this is what makes
  `min-instances: 0` + Cloud Run CPU throttling safe for a deployment with real job traffic. The
  `poll` tick keeps running underneath as a fallback for a lost/expired Cloud Tasks delivery; once a
  deployment has confirmed `cloud-tasks` mode is working, its poll interval is usually raised well past
  the 500ms default so it stays a rare backstop rather than a source of background CPU demand.

Either way, a run normally clears `PENDING` in well under a second. A run stuck in `PENDING` for any real
length of time is a symptom (engine trouble, a stuck upstream dependency), not a designed backlog.

**Real waiting happens at the self-hosted boundary.** A job with `runs-on: self-hosted` is handed to your
daemon and sits in `AWAITING_PICKUP` — and, importantly, **stays** `AWAITING_PICKUP` for the job's entire
execution once claimed; nothing flips it to `RUNNING`. What actually changes at claim time is
`WorkflowJobRun.claimedAt`, stamped the first time the daemon fetches the job's dispatch payload
(`GET .../jobs/{jobId}/dispatch-payload`) — idempotently, so a retry/restart re-fetch doesn't move it.
The daemon runs one job at a time by default (`maxConcurrentRuns`, see
[Concurrency and capacity](#concurrency-and-capacity)), so a burst of self-hosted dispatches backs up at
the daemon rather than on the server.

Note also that the *run* itself flips `PENDING` → `RUNNING` the moment its first job dispatches — even a
self-hosted job that's still sitting unclaimed in `AWAITING_PICKUP`. So a run genuinely waiting on a
runner is `RUNNING` at the run level, not `PENDING`; a raw `?status=` filter can't tell "waiting for a
runner" apart from "actually executing."

`GET .../runs` surfaces the unclaimed case on the run as `waitReason: "AWAITING_RUNNER"` (populated by
the list endpoint only, not by get/dispatch/cancel) — set only while at least one `AWAITING_PICKUP` job
on the run has no `claimedAt` yet, and cleared the moment it's claimed — and the UI shows it as
**"Waiting for runner."** If a self-hosted job is never claimed — daemon stopped, never upgraded,
whatever — the daily `cleanupStuckRuns` sweep fails any job still `AWAITING_PICKUP` after 24h (reason
`DAEMON_PICKUP_TIMEOUT`), so a dead daemon doesn't leave a run waiting forever. The schema also has a
`LOCAL_PICKUP_TIMEOUT` run status, shown in the UI as "Never picked up" — an older, run-level timeout for
a `PENDING_LOCAL_PICKUP` state that no current dispatch path actually enters; if you ever see a run end
this way, treat it as the same class of problem as `DAEMON_PICKUP_TIMEOUT` above.

**`concurrency: single` does not queue** (see [Workflow file format](#workflow-file-format)) — it's a
skip/reject gate, not a wait line. A due cron tick while a run is already active is **skipped** and
recorded in `workflow_schedule_skips` (`GET .../schedule-skips`), visible in the UI. A manual dispatch
against an active `concurrency: single` workflow is **rejected with 409** instead of being held for
later — neither case leaves anything queued to run once the active run finishes.

**Checking what's queued.** `GET .../runs` takes a repeated `?status=` filter (e.g.
`?status=PENDING&status=RUNNING`) for the raw run statuses; omit it to get every status, and an
unrecognized value 400s. Because a runner-blocked run is `RUNNING` at the run level (see above), `status`
alone can't express "queued" the way a human means it. A derived `?state=queued|running` filter covers
that instead: `queued` matches `PENDING`/`PENDING_LOCAL_PICKUP` **or** any run with an unclaimed
`AWAITING_PICKUP` job (regardless of its own run-level status); `running` matches
`RUNNING`/`CANCELLING` **minus** that same unclaimed-job case. The two are mutually exclusive but not
exhaustive over every non-terminal run — `LOCAL_PICKUP_TIMEOUT` is non-terminal and matches neither;
use `status` or the unfiltered list to see it. `state` and `status` are mutually exclusive; supplying
both 400s. The workflow's **Runs** tab uses `state`
to offer its Queued / Running / All filters, and the `list_workflow_runs` MCP tool takes the same `state`
parameter — callers should prefer it over `status=PENDING` for exactly the reason above — plus `status` as
the raw escape hatch.

---

## Cancelling a run

A **Cancel run** button appears on the run detail page for any run that's `PENDING` or `RUNNING` (also available as the `cancel_workflow_run` MCP tool, or `POST .../runs/{runId}/cancel`). Cancellation is a request, not an instant stop: the run immediately flips to **`CANCELLING`** — no further jobs are dispatched, and any job that hadn't started yet is marked `CANCELLED` right away — while whatever step is actually in flight is torn down best-effort by its execution backend. The run settles to the terminal **`CANCELLED`** once nothing is left running, typically within one poll interval (a few seconds). Cancelling an already-`CANCELLING` run is a no-op; cancelling a run that's already finished (`SUCCESS`/`FAILED`/`CANCELLED`) fails with a 409.

What "torn down" means depends on where the in-flight step is running:

- **Cloud Run** (`claude-code`/`agent` steps, conductor-hosted or a runtime target): the Cloud Run execution is cancelled via the Cloud Run Admin API — the container is stopped promptly.
- **Self-hosted worker VM** (`docker://` steps via `conductor-worker`): the container is `docker kill`ed then removed.
- **Kestra** (`kestra` steps): the Kestra execution is killed via its Executions API.
- **Self-hosted daemon** (`runs-on: self-hosted`, any step type, picked up by the `conductor` CLI's daemon): **soft-cancel only** — the job/step is marked `CANCELLED` and Conductor stops waiting on it, but the daemon isn't (yet) told to kill an already-running container. If one was in flight, it keeps running to completion in the background and its result is simply discarded. Hard-kill support for this path is a known follow-up.

### Cancelling every queued run

`POST .../runs/cancel-queued` cancels the queued backlog for a workflow in one call, returning
`{ cancelledCount }`. It's a bulk convenience over the same per-run cancellation path above — same
teardown semantics per execution backend, including the self-hosted soft-cancel limitation — not a
different mechanism.

A run qualifies when it's `PENDING`, **or** it has an unclaimed `AWAITING_PICKUP` job and no job that's
either `RUNNING` or an already-claimed `AWAITING_PICKUP` (a claimed one is actively executing on a
daemon). That's deliberately narrower than the UI's Queued *display* filter (`state=queued` above): a run
with a genuinely in-flight job is never bulk-cancelled just because some other job on it also happens to
be unclaimed — this endpoint only ever touches work that hasn't actually started.

This is a separate verb from pausing intake: disabling a workflow (the `enabled` toggle — see
[Auto-pause on repeated failures](#auto-pause-on-repeated-failures) for the automatic case) stops new
runs from being created but does **not** cancel whatever is already queued or running. Use
`cancel-queued` to drain an existing backlog and the `enabled` toggle to stop building a new one; use
both together to actually stop a workflow.

---

## Auto-pause on repeated failures

A workflow whose runs fail 5 times **in a row** (any trigger type — schedule, webhook, manual, or a
programmatic dispatcher) is automatically disabled (`enabled: false`) so it stops retrying — and
failing — indefinitely while someone investigates, rather than e.g. re-firing every 30 seconds off a
cron-driven dispatcher. This is the same `enabled` flag as the manual on/off toggle; the workflow row
additionally records *why* it went off:

| Field | Meaning |
|---|---|
| `autoPausedAt` | Timestamp the breaker tripped. Null for a plain human disable. |
| `autoPauseReason` | A free-form code — only `CONSECUTIVE_FAILURES` today, but not an enum, so a future trip condition doesn't need an API change. |
| `autoPausedRunId` | The run that tripped it, so the UI can link straight to the failure. |
| `consecutiveFailures` | Running count since the last `SUCCESS` (or since last re-enabled). |

Every trigger path that checks `enabled` before creating a run stops on its own once this trips —
including `knowledge-librarian`'s scheduler (see below), which otherwise has no other gate. A single
`SUCCESS` resets `consecutiveFailures` to 0. Re-enabling (`PATCH .../enabled` with `{"enabled": true}` —
the same toggle a human uses to manually disable/enable) always clears `autoPausedAt`/`autoPauseReason`/
`autoPausedRunId` and resets the counter, whether the workflow was auto-paused or manually disabled —
one action always gives a clean slate to retry. The workflow list and detail pages show a distinct
"Auto-paused" state (vs. plain "Disabled") with a banner linking to the failing run.

### Future: automated diagnosis

Not implemented yet — noted here as a deliberate extension point so it's designed for, not bolted on
later. The detection half of "a workflow is in trouble" already exists above (`consecutiveFailures` +
`autoPausedRunId`); what's missing is turning that into an actual explanation a human doesn't have to
dig for, building directly on the `errorReason`/`explanation`/`remediation` data every failed step
already carries (see each step type's failure-modes table):

- **Manual trigger (nearest-term):** a "Diagnose this failure" action on any failed run — in the UI or
  as a `diagnose_workflow_run(runId)`-style MCP tool — that walks the run's failed step(s), reads their
  `errorReason`/`explanation`/`remediation` plus surrounding log context, and produces a plain-language
  root cause and suggested fix. No new backend infrastructure required: it's a read over data that
  already exists once this is built.
- **Automatic trigger (further out):** hook the same diagnosis into the circuit breaker's trip point —
  when `autoPauseReason: CONSECUTIVE_FAILURES` fires, optionally kick off the diagnosis automatically
  (e.g. as a work item or notification with the suggested fix attached) instead of requiring a human to
  notice the auto-paused banner and investigate manually.
- Deliberately undecided: whether the "doctor" is a builtin system agent, a workflow, or a plain
  synchronous tool call — that's a call for whoever builds this, informed by real explanation/remediation
  data once Phase 2's taxonomy has seen production use.

## Failure notifications

A run that settles to `FAILED` — from any of the completion paths above (all-jobs-terminal, the 24h
stuck-run sweep, a self-hosted daemon's job-failure callback, a zero-jobs-enqueued dispatch, or the
legacy whole-run daemon report) — posts once to Discord if the project has a **Workflows** notification
channel configured (**Settings → Notifications → Add Channel**). The alert includes the workflow name,
the failing job/step and its `errorReason` when one is resolvable, the same human-readable
explanation/remediation text shown on the run detail page, and a link straight to the run. A `CANCELLED`
run never notifies.

The auto-pause trip (previous section) posts to the same **Workflows** channel as its own event — before
this, an auto-pause never produced a Discord message at all, since it belonged to no notification
channel.

## System-managed workflows

Some automation workflows are provisioned automatically by a Conductor feature rather than authored by a
project member — identified purely by a reserved workflow `name` (there's no schema-level "system" flag).
Two ship today, both seeded the first time a project turns on the **Knowledge Center**
(`knowledge_enabled` project setting — no dedicated frontend page yet, toggled via
`PATCH /api/v1/projects/{projectId}/settings`) and re-provisioned idempotently on every subsequent enable:

| Workflow name | Trigger | Purpose |
|---|---|---|
| `knowledge-librarian` | `workflow_dispatch` (`manual: false` — fired only by `LibrarianDispatchService`, see [Manual dispatch](#manual-dispatch)) | Files a batch of newly-ingested knowledge sources into wiki pages. `concurrency: single`. Its scheduler (`KnowledgeIngestScheduler`, 30s tick) also checks `enabled` before claiming sources, so an [auto-paused](#auto-pause-on-repeated-failures) librarian leaves sources untouched (not stuck mid-claim) until re-enabled. |
| `knowledge-bootstrap` | `workflow_dispatch` with a required `repo` input | Operator-triggered, once, to seed the wiki from an existing GitHub codebase. `concurrency: single` — enforced here since this is manual dispatch, so a second bootstrap can't be started while one is still running. |

Both are ordinary workflows once created — visible and re-editable in the workflow list like any other —
just authored by Conductor instead of a person. `knowledge-librarian` is a thin `uses: agent` step
dispatching to the seeded `knowledge-librarian` Agent (see [`docs/knowledge.md`](knowledge.md)) — its
runtime resolves per the [Runtimes](#agent--run-an-ai-agent) rules, so either the project's
**Claude Code (subscription)** credential or a `claude` API key works (subscription preferred when both
are configured) — no separate setup is needed for the `claude-code` runtime's Conductor MCP access, the
backend mints a run-scoped token automatically (see `conductor_mcp` above). `knowledge-bootstrap` remains
a raw `claude-code` step on `runs-on: cloud-run` with `conductor_mcp: true`, so it needs the project's
**Claude Code (subscription)** credential (see [Auth & runtime targets](#claude-code--run-claude-code-headlessly))
plus a `GITHUB_TOKEN` workflow secret to bootstrap from a private repo.

See [`docs/knowledge.md`](knowledge.md) for the full Knowledge Center domain model — ingestion envelope,
page format, and pipeline these workflows are part of.

---

## Self-hosted setup

### Prerequisites

- A machine with Docker installed and the Docker daemon running
- Node.js 20 or later
- The `conductor` CLI installed and authenticated (`conductor init`), **kept up to date** — the daemon and backend speak a versioned dispatch protocol (currently protocol 2), and an out-of-date CLI won't pick up self-hosted jobs at all until upgraded

### Running the daemon

Self-hosted workflow execution is handled by the same **conductor daemon** used for file sync. If you've already run `conductor init`, just start the daemon:

```bash
conductor start
```

The daemon receives a per-job dispatch event once a self-hosted job becomes ready to run — i.e. **after** all of its `needs` dependencies finish — not at the start of the whole workflow run. This means a single workflow run can freely mix Conductor-hosted and self-hosted jobs (`needs`/`${{ secrets.* }}` interpolate correctly across the boundary either way): only the self-hosted jobs are handed to your daemon, hosted jobs run on Conductor's infrastructure as usual, and each side waits on the other via normal `needs` ordering. On pickup, the daemon fetches the job's interpolated inputs (env, per-step prompts, a short-lived run token) directly from the backend — nothing secret sits in the dispatch event itself.

For each step in the job, the daemon pulls the appropriate Docker image, runs the container (or, for a `claude-code` step, runs `conductor-claude-entrypoint` inside it), streams logs back, and reports the result. No separate worker process or HTTP server is required.

Verify the daemon is running and check active workflow runs:

```bash
conductor dashboard
```

> **Docker socket access:** The daemon spawns containers via Docker. Make sure the user running `conductor start` has permission to use Docker (i.e. is in the `docker` group, or run with `sudo`).

### Subscription auth for claude-code steps

`claude-code` steps with `runs-on: self-hosted` authenticate as your own Claude Pro/Max subscription rather than a metered API key. One-time setup on the daemon host:

```bash
claude setup-token
conductor config set-claude-code-oauth-token <token>
```

`claude setup-token` (part of the Claude CLI) produces a subscription OAuth token; `conductor config set-claude-code-oauth-token` stores it in `~/.conductor/config.json` on that machine. The token is injected directly into the step's container by the daemon and is never sent to or stored by the Conductor backend. Remove it with `conductor config unset-claude-code-oauth-token`.

If no token is configured, a `claude-code` step dispatched to that daemon fails immediately with `errorReason: CLAUDE_SUBSCRIPTION_NOT_CONFIGURED` rather than silently falling back to any other credential.

`runs-on: cloud-run` and named runtime targets also use subscription auth, but via a separate project-level credential (**Settings → AI Providers → Claude Code subscription**) rather than this daemon-local token — see "Auth & runtime targets" under the `claude-code` step above.

### Runner image

When a `docker` step uses `uses: docker://` (no image name), or a job runs a `claude-code` step, the daemon pulls the default Conductor runner image:

```
ghcr.io/cliangdev/conductor-runner:3
```

This image includes:

| Tool | Version |
|------|---------|
| Node.js | 20 |
| Python | 3.12 |
| Docker CLI | latest stable |
| GitHub CLI (`gh`) | latest stable |
| Claude CLI | pinned (bumped deliberately per release) |
| `curl`, `git`, `jq` | latest stable |
| `conductor-claude-entrypoint` | self-reporting entrypoint used by `claude-code` steps (see below) |

A **dedicated claude-code image** also exists (`runner-image/Dockerfile.claude-runner`): node-slim + the same pinned Claude CLI + `conductor-claude-entrypoint`, plus `git`/`gh`/`curl`/`jq` (so `credentials:`-based steps can act on an issued token), without the Python/Docker-CLI tooling above. Published to `ghcr.io/cliangdev/conductor-claude-runner` (`:latest` and immutable `:sha-<commit>` tags) by the **Publish Claude Runner Image** workflow (`.github/workflows/publish-claude-runner.yml`), which builds and pushes automatically whenever `Dockerfile.claude-runner`, the entrypoint, or its self-test changes on `main` — no manual version tag needed, unlike the general-purpose image's `runner-v*.*.*`-tag-triggered release. Today it's consumed via a [runtime target](#runtime-targets-bring-your-own-cloud-run)'s image field; making it the default image for self-hosted claude-code dispatch is tracked in #268. Unlike the general-purpose image, it bakes in `@cliangdev/conductor` (the CLI/MCP server) at build time — pinned to an exact version via `CONDUCTOR_TOOLS_VERSION` (same convention as the Claude CLI pin), not left to resolve "latest" implicitly. Bump that `ARG` deliberately in a Dockerfile change (which also triggers a rebuild) rather than relying on an incidental rebuild to pick up a new npm release.

The container runs as a non-root `runner` user and ships with **no default `ENTRYPOINT`/`CMD`** — a plain `docker` step's `run:` script executes as before; a `claude-code` step explicitly invokes `conductor-claude-entrypoint`, which materializes the step's `inputs` under `/conductor/inputs/`, optionally wires up the Conductor MCP server, runs `claude -p` with the step's flags, streams logs, and self-reports the result (outputs + `errorReason`) back to Conductor — the same entrypoint runs unmodified whether the launcher is the self-hosted daemon or a Cloud Run Job execution. Every run's first log line states which pinned versions it's actually running (e.g. `→ container started (claude 2.1.206, conductor-tools 0.11.4)`), so confirming what shipped never requires execing into a container.

**Runner protocol label.** Both images carry a `dev.conductor.runner.protocol` OCI label declaring they honor this contract. When a [BYO GCP runtime target](#runtime-targets-bring-your-own-cloud-run) provisions, `GcpConnector.verifyImage()` fetches the image's manifest and config blob via the Docker Registry v2 API (Artifact Registry's management API exposes no labels field, so this is the only way to read it) and checks for the label — a custom image that doesn't invoke `conductor-claude-entrypoint` and set this label will provision with a "could not verify the runner contract" warning rather than a hard failure, since the check is advisory (network/auth issues degrade to the same warning, never block an image that otherwise exists).

You can specify any other public image in the `uses` field for `docker` steps (this has no effect on `claude-code` steps, which always use the runner image):

```yaml
- uses: docker://python:3.12-slim
  run: pip install -r requirements.txt && python main.py
```

Or use a private image from your registry (make sure the worker VM has credentials to pull it):

```yaml
- uses: docker://registry.yourcompany.com/build-tools:v3
  run: ./scripts/build.sh
```

### Concurrency and capacity

The daemon runs **1 self-hosted job at a time** by default. To increase the limit, set `maxConcurrentRuns` in `~/.conductor/config.json`:

```json
{
  "maxConcurrentRuns": 3
}
```

When all slots are occupied, incoming jobs are queued locally and processed in order.

### Crash recovery

If the worker process restarts while jobs are running, it automatically detects any orphaned containers on startup and reports them as failed back to Conductor. This prevents runs from hanging indefinitely after a worker restart.

### Configuration reference

The daemon reads from `~/.conductor/config.json`, written by `conductor init`. Self-hosted workflow settings:

| Key | Default | Description |
|-----|---------|-------------|
| `maxConcurrentRuns` | `1` | Maximum number of self-hosted Docker jobs to run simultaneously. |

---

## Building workflows with Claude

Claude can design and create workflows for you based on a plain-language description of the business goal.

### How it works

1. **Invoke the skill** — Type `/conductor:workflow` in Claude Code and describe what you want:
   > "Build a workflow that analyzes our landing page SEO performance weekly and posts a summary to Discord."

2. **Claude runs discovery** — it calls `list_integration_tools` to see which data sources are already connected, `list_connector_catalog` to recommend integrations you haven't connected yet, `list_workflow_secrets` to see what secret keys already exist (never their values), `list_workflow_runs` to check recent run history, and `list_workflows` to understand your existing conventions. You do not need to know connector names, credential keys, or YAML syntax.

3. **Claude asks clarifying questions** — trigger cadence, output destination (Discord, Slack, Conductor issue), and any scope constraints.

4. **Claude designs and creates the workflow** — it writes the YAML using `uses: integration` for connected data sources (credentials are never embedded), creates the workflow as a DRAFT, verifies it, publishes it, and optionally dispatches a test run.

### Prerequisites

**Integrations must be connected before Claude can reference them.** Go to **Settings → Integrations** and connect any data sources your workflow will use (Google Search Console, PostHog, RevenueCat, GCP Billing). Once a connection is ACTIVE, Claude will automatically discover it via `list_integration_tools`.

For guidelines on adding or updating the MCP tools that power this skill, see [`docs/mcp-tool-guidelines.md`](mcp-tool-guidelines.md).

For data sources without a built-in connector, add the credentials as [workflow secrets](#outputs-and-interpolation) and Claude will use `${{ secrets.KEY }}` interpolation in `http` steps instead.

## Lifecycle workflows (statecharts)

Everything above describes **automation** workflows (triggers + jobs + steps). Conductor also has a second,
orthogonal workflow kind: a **lifecycle** workflow — a statechart that governs how a **Work Item** moves through
named stages, with optional review gates and skill-driven transitions. The built-in `ENGINEERING` lifecycle
(PRD → In Review → Ready → In Progress → Code Review → Done) is what the `conductor:prd` / `conductor:implement`
/ `conductor:fix` skills drive. The two kinds are distinguished by a server-derived `kind` (`AUTOMATION` vs
`LIFECYCLE`) and never inferred from payload shape.

A lifecycle is **data, not code** — you author one for any domain (marketing, design, docs) the same way, without
a backend change:

1. **Author it** — run `/conductor:workflow` and choose the lifecycle path (or ask to "define how our marketing
   work moves through stages"). Claude discovers your conventions (`list_workflows`, `list_skills`, `list_agents`),
   designs the statechart (area, noun, Work Item types, statuses, transitions, review gates, skill steps), then
   `create_workflow({definition})` → `get_workflow` → `publish_workflow`.
2. **Drive Work Items through it** — the *same* generic tools work for every lifecycle: `create_work_item({workflow,
   type, title})` binds an item to your lifecycle (the `workflow` slug is **required** — discover it via
   `list_workflows({kind:"LIFECYCLE"})`), and `get_available_transitions` → `transition_work_item` walk it. No
   per-domain tools.

### Binding a skill to a transition (custom skills)

A transition can run a Claude Code skill (`steps: [{kind: "skill", skill: "<id>"}]`) — the analogue of
`conductor:implement` on the engineering `Start work` edge. Publish **rejects a skill the project hasn't
registered**, so for a new domain skill (e.g. `marketing:seo-report`) register it first:

- `list_skills` — shows what's bindable: shipped built-ins (`conductor:*`) plus your project's registered skills.
- `register_skill({skillId, label?, description?})` — registers a project-scoped skill id so a lifecycle can bind
  it and publish, **with no backend redeploy**. Idempotent; built-ins need no registration.

Registering a skill only makes the id *bindable* — the skill's behavior lives in the Claude Code skill/command the
user installs. Only ADMIN/CREATOR can register skills or publish workflows.

### System triggers (event-driven auto-transitions)

A transition can declare a `trigger` so a **system event** advances a Work Item automatically, instead of a human
choosing the move. The trigger id must be one the backend knows how to fire (a *registered system trigger*); Publish
rejects an unknown trigger with `transition … uses unknown system trigger '…'`. Two ship today:

| Trigger | Fires when | Review gate |
| --- | --- | --- |
| `pr_merged` | a linked GitHub pull request merges | **bypassed** — the merge is the authority |
| `status_changed` | the Work Item's status changes (any move) | **honored** — a review-gated edge only fires once its Review is satisfied |

```yaml
transitions:
  - from: IN_REVIEW
    to: APPROVED
    label: Auto-approve
    trigger: status_changed        # fires when the item reaches IN_REVIEW, then advances to APPROVED
```

`status_changed` transitions **cascade**: one status change fires the next declared edge, hop by hop, until no
`status_changed` edge matches (a repeated status or a hard hop cap stops any statechart cycle). This lets a lifecycle
chain automatic stages without a human between them. Adding a *new* system trigger id is a backend change (a registry
entry in `system-triggers.json` plus the service that fires it), not a schema edit — `connector_event` and other
connector-driven triggers remain reserved.

> **Still engineering-shaped** (tracked in a #240 follow-up): step `kind` is a fixed enum
> (`skill`/`http`/`notify`/`set_field`/`create_sub_items`), and the on-disk CLI layout (`~/.conductor/.../issues/`)
> still uses the legacy folder name. A user-authored lifecycle built on **manual, skill, review, and
> `status_changed`** transitions is fully supported today.
