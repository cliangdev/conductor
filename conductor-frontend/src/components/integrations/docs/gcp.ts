const doc = `## What it does

Connect your own Google Cloud project so \`claude-code\` workflow steps run as **Cloud Run Job executions in your GCP project** — bring-your-own-cloud — instead of on Conductor-managed infrastructure. This gives you control over cost, region, image contents, and IAM boundaries for any workflow step that hands a prompt to Claude Code.

## How authentication works

Connecting Google Cloud requires a **service account JSON key**, pasted or uploaded once. The key is stored **encrypted at rest** (the same KMS envelope used for other integration credentials) and is **write-only** — it is never returned by the API after you save it.

The service account needs:

| Role | Why |
|---|---|
| \`roles/run.developer\` | Create, update, and run the Cloud Run Job |
| \`roles/iam.serviceAccountUser\` | Act as the Job's runtime service account |

The **runtime** service account — the identity the Job actually executes as — separately needs \`roles/artifactregistry.reader\` so it can pull your container image.

## Runtime targets

A **runtime target** is a named Cloud Run destination, managed on this integration's **Overview** tab, that workflow steps reference by name:

\`\`\`yaml
runs-on: my-target
\`\`\`

\`conductor\`, \`self-hosted\`, and \`cloud-run\` are reserved built-in names — pick anything else for your own targets.

Provisioning a target is **synchronous**: when you create or edit one, Conductor verifies the image exists in your Artifact Registry, then creates or updates the Cloud Run Job in your project — the image, the \`conductor-claude-entrypoint\` command, and \`--max-retries 0\` are all pinned on that Job resource. A non-fatal warning is recorded if the image's \`dev.conductor.runner.protocol\` OCI label can't be verified — the image may still work, it just couldn't confirm the runner contract.

Targets carry one of three statuses:

| Status | Meaning |
|---|---|
| \`PROVISIONING\` | Create/update is in flight |
| \`ACTIVE\` | Ready to run workflow steps |
| \`ERROR\` | Provisioning failed — see the reason on the row, fix it, then **Retry provisioning** |

Deleting this **gcp connection** flips all of its dependent runtime targets to \`ERROR\`. Deleting a single **runtime target** only removes Conductor's record of it — the Cloud Run Job itself is left in place in your GCP project.

## How a claude-code step executes

\`\`\`mermaid
sequenceDiagram
    participant WF as Workflow run
    participant BE as Conductor backend
    participant CR as Cloud Run Job (your GCP project)
    participant EP as conductor-claude-entrypoint
    WF->>BE: claude-code step (runs-on: my-target)
    BE->>BE: resolve runtime target (must be ACTIVE)
    BE->>CR: launch execution of pre-provisioned Job
    Note over BE,CR: Claude Code subscription token injected by backend — never in YAML
    CR->>EP: container starts
    EP->>EP: materialize step inputs under /conductor/inputs/
    EP->>EP: wire Conductor MCP server (if conductor_mcp: true — needs project API key)
    EP->>EP: run claude -p with subscription auth
    EP-->>BE: stream logs, self-report outputs / errorReason
    BE-->>WF: step outputs
\`\`\`

## Using it in workflows

Reference a runtime target by name in \`runs-on\`, then use a \`claude-code\` step as normal:

\`\`\`yaml
jobs:
  analyze:
    runs-on: my-target
    steps:
      - id: seo
        uses: claude-code
        with:
          prompt: Summarize the attached data.
\`\`\`

**Errors to expect**

| errorReason | Meaning |
|---|---|
| \`RUNTIME_TARGET_NOT_FOUND\` | The \`runs-on\` name doesn't match any runtime target in this project. |
| \`RUNTIME_TARGET_NOT_READY\` | The target exists but isn't \`ACTIVE\` yet — fix it on this integration's **Overview** tab, then retry the run. |

**Auth note** — \`claude-code\` steps are **subscription-auth only** on every runtime, including runtime targets. The project's Claude Code (subscription) credential is configured under **Agents → Providers** and injected by the backend at execution time — it is never written into workflow YAML.
`

export default doc
