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
  - [Running the worker](#running-the-worker)
  - [Connecting to Conductor](#connecting-to-conductor)
  - [Runner image](#runner-image)
  - [Environment reference](#environment-reference)

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

- A Linux VM (or any machine) with Docker installed and the Docker daemon running
- Node.js 20 or later
- Network path from Conductor's backend to your worker (the worker must be reachable via HTTP from Conductor's servers)

### Running the worker

Install and start `conductor-worker`:

```bash
npm install -g @conductor/worker

CONDUCTOR_WORKER_SECRET=your-secret-here \
CONDUCTOR_BACKEND_URL=https://api.conductor.app \
conductor-worker start
```

Or run it as a Docker container (recommended for production):

```bash
docker run -d \
  --name conductor-worker \
  --restart unless-stopped \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -p 3001:3001 \
  -e CONDUCTOR_WORKER_SECRET=your-secret-here \
  -e CONDUCTOR_BACKEND_URL=https://api.conductor.app \
  ghcr.io/cliangdev/conductor-worker:latest
```

The worker listens on port 3001 by default. Verify it's running:

```bash
curl http://localhost:3001/health
# {"status":"ok"}
```

> **Docker socket access:** The worker needs access to the Docker socket (`/var/run/docker.sock`) to start containers. When running the worker itself inside Docker, mount the socket as shown above.

### Connecting to Conductor

Once the worker is running, configure your project in Conductor:

1. Open **Project Settings** in the Conductor UI
2. Under **Workflow**, enter:
   - **Worker URL** — the public or private URL where your worker is reachable from Conductor (e.g. `http://10.0.1.5:3001` or `https://worker.yourcompany.internal`)
   - **Worker secret** — the same `CONDUCTOR_WORKER_SECRET` value you set on the worker

Conductor will use this URL and secret to dispatch docker jobs to your worker whenever a workflow job specifies `runs-on: self-hosted`.

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

The worker handles multiple jobs concurrently. The default maximum is **5 concurrent jobs**. Adjust this with the `MAX_CONCURRENT_JOBS` environment variable:

```bash
MAX_CONCURRENT_JOBS=10 conductor-worker start
```

When the worker is at capacity, job submissions are rejected with HTTP 503 and retried automatically by Conductor.

### Crash recovery

If the worker process restarts while jobs are running, it automatically detects any orphaned containers on startup and reports them as failed back to Conductor. This prevents runs from hanging indefinitely after a worker restart.

### Environment reference

#### conductor-worker environment variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `CONDUCTOR_WORKER_SECRET` | Yes | — | Shared secret used to authenticate requests from Conductor. Choose a long random string. |
| `CONDUCTOR_BACKEND_URL` | Yes | — | Base URL of the Conductor backend (e.g. `https://api.conductor.app`). Used for log and output callbacks. |
| `PORT` | No | `3001` | Port the worker HTTP server listens on. |
| `MAX_CONCURRENT_JOBS` | No | `5` | Maximum number of Docker jobs to run simultaneously. |

#### Project settings

| Setting | Description |
|---------|-------------|
| **Worker URL** | The URL Conductor uses to reach your worker when dispatching `self-hosted` jobs. |
| **Worker secret** | The `CONDUCTOR_WORKER_SECRET` value — must match what you set on the worker. |
| **Run token TTL** | How long ephemeral tokens are valid for a single job run. Default is 24 hours; valid range is 1–168 hours. |
