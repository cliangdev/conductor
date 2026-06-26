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
  - [Loops](#loops)
  - [Conditions](#conditions)
- [Execution modes](#execution-modes)
  - [Conductor-hosted](#conductor-hosted)
  - [Self-hosted](#self-hosted)
- [Self-hosted setup](#self-hosted-setup)
  - [Prerequisites](#prerequisites)
  - [Running the daemon](#running-the-daemon)
  - [Runner image](#runner-image)
  - [Concurrency and capacity](#concurrency-and-capacity)
  - [Configuration reference](#configuration-reference)

---

## How workflows work

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

An optional `concurrency` key can be set to `"single"` to ensure only one run of the workflow is active at a time (useful for scheduled jobs):

```yaml
concurrency: single
```

---

### Triggers

The `on` block defines what starts the workflow. Multiple triggers can be combined.

#### Manual dispatch

Adds a **Run Now** button in the workflow UI.

```yaml
on:
  workflow_dispatch: {}
```

#### Webhook

A unique webhook URL is generated per workflow. POST to it from any external service (GitHub Actions, Zapier, etc.) to trigger a run. The request body is available as `${{ event.* }}` during the run.

```yaml
on:
  webhook: {}
```

#### Issue status change

Fires when any issue in the project changes status. Use `filters.status` to narrow it to a specific target status.

```yaml
on:
  conductor.issue.status_changed:
    filters:
      status: "IN_REVIEW"     # only fire when an issue moves to IN_REVIEW
```

Available event fields: `event.toStatus`, `event.fromStatus`, `event.issueId`.

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
| `runs-on` | Execution mode: `conductor` (default) or `self-hosted`. See [Execution modes](#execution-modes). |
| `if` | Expression evaluated before the job starts. If false, the job is skipped. |
| `steps` | List of steps to execute in order. |
| `loop` | Repeat this job up to `max_iterations` times until a condition is met. See [Loops](#loops). |

When an upstream job fails, all jobs that depend on it are marked **skipped** rather than failed, making it easy to see exactly where a run went wrong.

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

#### `kestra` — Delegate to a Kestra flow

Triggers a flow in your Kestra instance and optionally waits for it to finish.

```yaml
- id: run-etl
  type: kestra
  namespace: myorg.data
  flow_id: nightly-etl
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
| `inputs` | — | Input values passed to the Kestra flow. Interpolated. |
| `wait` | `true` | Wait for the flow to complete before continuing. |
| `timeout_minutes` | `60` | How long to wait before timing out. |
| `fail_on_warning` | `false` | Treat Kestra WARNING execution state as a failure. |
| `outputs` | — | Map of output key → dot-notation path into the Kestra execution response. |

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

---

### Outputs and interpolation

Use `${{ ... }}` to inject dynamic values into any string field.

| Expression | Value |
|------------|-------|
| `${{ event.FIELD }}` | Field from the trigger event payload |
| `${{ secrets.SECRET_NAME }}` | Project secret (name must be uppercase with underscores) |
| `${{ steps.STEP_ID.outputs.KEY }}` | Output from a step in the current job |
| `${{ needs.JOB_ID.outputs.KEY }}` | Output from a completed upstream job |
| `${{ loop.iteration }}` | Current loop iteration number (1-based) |

Unknown references resolve to an empty string rather than erroring.

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

A skipped job shows in the run history as **SKIPPED**, and any jobs that depend on it are also skipped.

---

## Execution modes

Docker steps can run in one of two modes, controlled by the `runs-on` field on the job.

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

Self-hosted jobs require a running **conductor-worker** process on your VM. See [Self-hosted setup](#self-hosted-setup) below.

---

## Self-hosted setup

### Prerequisites

- A machine with Docker installed and the Docker daemon running
- Node.js 20 or later
- The `conductor` CLI installed and authenticated (`conductor init`)

### Running the daemon

Self-hosted workflow execution is handled by the same **conductor daemon** used for file sync. If you've already run `conductor init`, just start the daemon:

```bash
conductor start
```

The daemon polls Conductor for pending self-hosted jobs, pulls the appropriate Docker image, runs the container, streams logs back, and reports the result. No separate worker process or HTTP server is required.

Verify the daemon is running and check active workflow runs:

```bash
conductor dashboard
```

> **Docker socket access:** The daemon spawns containers via Docker. Make sure the user running `conductor start` has permission to use Docker (i.e. is in the `docker` group, or run with `sudo`).

### Runner image

When a `docker` step uses `uses: docker://` (no image name), the worker pulls the default Conductor runner image:

```
ghcr.io/cliangdev/conductor-runner:latest
```

This image includes:

| Tool | Version |
|------|---------|
| Node.js | 20 |
| Python | 3.12 |
| Docker CLI | latest stable |
| GitHub CLI (`gh`) | latest stable |
| Claude CLI | latest stable |
| `curl`, `git`, `jq` | latest stable |

The container runs as a non-root `runner` user. You can specify any other public image in the `uses` field:

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

2. **Claude runs discovery** — it calls `list_integration_tools` to see which data sources are already connected, and `list_workflows` to understand your existing conventions. You do not need to know connector names, credential keys, or YAML syntax.

3. **Claude asks clarifying questions** — trigger cadence, output destination (Discord, Slack, Conductor issue), and any scope constraints.

4. **Claude designs and creates the workflow** — it writes the YAML using `uses: integration` for connected data sources (credentials are never embedded), creates the workflow as a DRAFT, verifies it, publishes it, and optionally dispatches a test run.

### Prerequisites

**Integrations must be connected before Claude can reference them.** Go to **Settings → Integrations** and connect any data sources your workflow will use (Google Search Console, PostHog, RevenueCat, GCP Billing). Once a connection is ACTIVE, Claude will automatically discover it via `list_integration_tools`.

For data sources without a built-in connector, add the credentials as [workflow secrets](#outputs-and-interpolation) and Claude will use `${{ secrets.KEY }}` interpolation in `http` steps instead.
