# Conductor

Coordination platform for agentic organizations. AI agents do the work (specs, code, campaigns, wiki); humans review, approve, and steer. Pillars: Work Items + Reviews, Workflows (lifecycle statecharts + YAML automation), Agents, Knowledge Center, third-party integrations.

## Maintaining This File

Keep this file under 200 lines. Review changes to it like code. Rules for what belongs here:

- **Include**: build commands, directory layout, monorepo structure, coding conventions, team norms, pointers to deeper docs
- **Exclude**: procedural workflows, release checklists, ops runbooks → put those in `.claude/skills/` or `docs/`
- **Don't** encode enforcement rules ("never do X") here — use hooks or permission settings instead
- Any section growing past ~15 lines should move to a dedicated doc or skill with a pointer here

## Maintaining README.md

README.md is the public front door: positioning, pillar list, architecture diagram (mermaid), repo layout, quick start, doc index. Update it **in the same PR** as any change that alters one of those — a platform pillar added/removed/renamed, a new top-level package, a new execution mode, or a changed quick-start command. Everything else lives in `docs/` — link from the README, never inline. No per-feature sections; keep it readable in one sitting (~150 lines).

## Project Structure

```
conductor/
├── conductor-backend/     # Spring Boot 4.1.0, Java 21, Maven
├── conductor-frontend/    # Next.js 16, TypeScript, Tailwind, shadcn/ui
├── conductor-tools/       # @cliangdev/conductor — CLI + MCP server (single npm package)
├── conductor-worker/      # self-hosted workflow job runner (Express + Docker socket)
└── runner-image/          # container images for workflow step execution (ghcr conductor-runner)
```

## conductor-backend

Spring Boot REST API. OpenAPI-first — see [`docs/api-guidelines.md`](docs/api-guidelines.md).

```
src/main/java/com/conductor/
├── agent/         # Agents: providers (BYO keys), runs (ReAct loop), tools
├── config/        # Spring Security, GCP storage, RestTemplate
├── controller/    # Legacy /api/v1 controllers (issue vocabulary)
├── dto/           # Generated request/response DTOs
├── entity/        # JPA entities
├── exception/     # GlobalExceptionHandler, typed exceptions (RFC 7807)
├── integration/   # Connector framework + connectors (github, discord, gcp, ...)
├── internal/      # /internal/v1 controllers (run-token auth, not JWT)
├── knowledge/     # Knowledge Center: sources, pages, librarian dispatch
├── memory/        # Agent memory: extraction, consolidation, retrieval, retention
├── repository/    # Spring Data JPA repositories
├── security/      # JWT filter, API key filter, Firebase token verification
├── service/       # Business logic
├── v2/            # Current Work Item API surface
└── workflow/      # Execution engine, step executors, YAML model, lifecycle statecharts

src/main/resources/
├── openapi.yaml               # External /api/v1 (legacy issue vocabulary)
├── openapi-v2.yaml            # External v2 Work Item surface
├── openapi-internal.yaml      # Internal /internal/v1
└── db/migration/V*.sql        # Flyway migrations (PostgreSQL)
```

**Auth**: Firebase Google OAuth → app JWT (HTTP-only cookie). API key auth also supported for CLI.

**Key env vars**: `FIREBASE_PROJECT_ID`, `FIREBASE_SERVICE_ACCOUNT_KEY`, `JWT_SECRET`, `DATABASE_URL`, `RESEND_API_KEY`, `GCP_STORAGE_BUCKET_NAME`, `GCP_SERVICE_ACCOUNT_KEY`, `FRONTEND_URL`. `GCP_CLOUDRUN_PROJECT_ID`/`GCP_CLOUDRUN_REGION`/`GCP_CLOUDRUN_CLAUDE_JOB_NAME` configure the builtin `claude-code` Cloud Run target — optional once a project designates its own runtime target (Settings → AI Providers → Runtime), see [`docs/workflows.md`](docs/workflows.md#cloud-run).

**Run**: `mvn spring-boot:run` · **Test**: `mvn test`

## conductor-frontend

Next.js 16 App Router. Auth via `AuthContext` (Firebase JS SDK + app JWT). Project scope via `ProjectContext`.

```
src/
├── app/
│   ├── app/projects/[projectId]/
│   │   ├── [area]/[noun]/     # Work Item list, workflow-scoped (e.g. engineering/issues)
│   │   │   └── [displayId]/   # Work Item detail: doc viewer + comments + review panel
│   │   ├── workflows/         # Automation (YAML) list/editor + lifecycle/ statechart editors
│   │   ├── agents/            # Agent list, creation, settings
│   │   ├── knowledge/         # Wiki pages + ingestion sources
│   │   ├── memory/            # Workspace agent-memory browser
│   │   ├── integrations/      # Connector catalog + connections
│   │   ├── docs/              # Project docs (folders, versions)
│   │   └── settings/          # general, members, api-keys, cli, notifications, secrets
│   ├── invites/[token]/accept/
│   └── login/
├── components/      # per-domain groups: agents, comments, docs, integrations, issues,
│                    # knowledge, layout, markdown, members, reviews, workflow, workitems, ui
│   └── ui/          # shadcn/ui primitives (Badge, Button, Avatar, etc.)
├── contexts/        # AuthContext, ProjectContext
└── lib/api.ts       # apiGet / apiPost / apiPatch / apiDelete helpers
```

**Key env vars**: `NEXT_PUBLIC_API_URL`, `NEXT_PUBLIC_FIREBASE_*`

**Run**: `npm run dev` · **Test**: `npx vitest`

## conductor-tools

`@cliangdev/conductor` — single npm package combining the CLI and MCP server.

```
src/
├── commands/    # CLI commands: mcp, start, stop, status, dashboard, login, logout, init, config, doctor, lint
├── daemon/      # watcher.ts — chokidar file watcher, 500ms debounce
├── lib/         # API client, config loader
└── mcp/         # MCP server (stdio): work items, documents, project docs, workflows, comments, integrations
```

Local files at `~/.conductor/{projectId}/issues/**`. Offline queue at `~/.conductor/sync-queue.json`.

**Build**: `npm run build` · **Test**: `npx vitest` · **Config**: `~/.conductor/config.json`

**Releasing**: Bump version on your branch (`cd conductor-tools && npm version <patch|minor|major> --no-git-tag-version`), then PR + merge. CI (`release-cli.yml`) auto-publishes to npm on merge when `package.json` changes. See `.github/workflows/release-cli.yml` for the full flow and recovery cases.

## Data Model (key tables)

A **`project` is the single top-level "Workspace"** — "Workspace" is the user-facing name; the table/entity/route stay `project(s)` internally. Membership in `project_members` is the *only* access gate (always check via `ProjectSecurityService`). There is intentionally no org/team layer above projects.

`users` → `project_members` (ADMIN/CREATOR/REVIEWER) → `projects`  
`work_items` → `documents` (GCP-backed, signed URLs); status/type are Workflow-defined strings, not enums  
`work_item_reviewers` → `reviews` (APPROVED/CHANGES_REQUESTED/COMMENTED)  
`comments` + `comment_replies` (line-level or selection-based anchors)  
`project_settings` (Discord webhook URL)  
`invites`, `api_keys`  
`workflow_definitions` → `workflow_definition_versions` (immutable published snapshots) → `workflow_runs`/`workflow_job_runs`/`workflow_step_runs`; plus `workflow_secrets`, `workflow_artifacts`, `runtime_targets` (BYO Cloud Run)  
`connections` (connector framework) + `webhook_event` (inbound) + `action_invocation` (outbound idempotency/retry)  
`connector_feed` (scheduled per-connection pull binding, declared via a connector's `ingest[]`) → `connector_feed_digest` (per-period change report); `disposition_policy` (routes a signal type to a handling lane) — see [`docs/knowledge.md`](docs/knowledge.md#metrics-digests)  
`agents` → `agent_runs` (ReAct transcripts); `provider_credentials` (BYO model keys, KMS envelope); `config.addressable` opts an agent into direct conversation  
`conversations` → `conversation_messages` (USER/ASSISTANT turns) — multi-turn chat with an addressable agent over the REST API or Discord's `/ask`, see [`docs/conversations.md`](docs/conversations.md)  
`agent_memories` — durable facts/decisions/preferences/events an agent accumulates across conversations, bi-temporal (`valid_from`/`valid_to`/`superseded_by`), see [`docs/memory.md`](docs/memory.md)  
`knowledge_sources` → `knowledge_pages`/`knowledge_page_revisions` (+ links) — agent-maintained wiki, see [`docs/knowledge.md`](docs/knowledge.md)

**Future eng/marketing grouping** should use **labels + saved views** (or a nullable `group` tag on `project_members`), *not* a nested container above projects — that two-level org→project model was deliberately removed for simplicity.

## Claude Integration (.claude/)

`.claude/` at the repo root provides skills and agents for Claude Code users:

- `commands/conductor` — the `conductor` slash command (installed to `~/.claude/` via `conductor init`)
- `skills/` — `conductor-coder`, `agent-creator`, `ux-ui-design`
- `agents/` — custom subagent definitions

Source of truth for these assets is `conductor-tools/assets/claude/` — edit there, not in `.claude/` directly.

## CI / Deployment

**PR deployments** — add a label to deploy to Cloud Run:
- `deploy-backend` → deploys `conductor-backend`
- `deploy-frontend` → deploys `conductor-frontend`

To redeploy after new commits: remove then re-add the label. Each run posts deploy status as a PR comment.

**CLI release** — handled automatically by `release-cli.yml` on merge (see conductor-tools section).

## Logs

Use `scripts/logs.sh` (requires `CONDUCTOR_GCP_PROJECT` env var):

```bash
export CONDUCTOR_GCP_PROJECT=<project>
./scripts/logs.sh                        # backend, last 50 lines
./scripts/logs.sh frontend --since 1h   # frontend, last 1 hour
```

See `scripts/gcloud-alias-example.sh` for a persistent shell alias.

## Key Docs

- [`docs/api-guidelines.md`](docs/api-guidelines.md) — OpenAPI-first workflow, external vs internal API split, REST conventions. **Read before creating or updating any API.**
- [`docs/workflows.md`](docs/workflows.md) — Workflow YAML format, trigger types, step types, execution modes, self-hosted runner setup.
- [`docs/knowledge.md`](docs/knowledge.md) — Knowledge Center: ingestion envelope, wiki page model, librarian workflows.
- [`docs/conversations.md`](docs/conversations.md) — Conversations & the CEO agent: addressable agents, the conversation REST API, Discord `/ask` setup.
- [`docs/memory.md`](docs/memory.md) — Agent memory: dual-phase extraction/consolidation write path, retrieval scoring, bi-temporal lifecycle, memory-vs-knowledge promotion.
- [`docs/ai-providers.md`](docs/ai-providers.md) — AI Providers: BYO-key model, registered providers, connection states, model discovery, extending with a new provider.
- [`docs/mcp-tool-guidelines.md`](docs/mcp-tool-guidelines.md) — MCP tool design principles: context budget, action–verify pattern, dispatch–status pattern, checklist. **Read before creating or updating any MCP tool.**
- [`docs/design-system.md`](docs/design-system.md) — Frontend design system: tokens (light + dark), typography, status ramp, required primitives, page-chrome patterns, anti-patterns. **Read before any UI work.**
- [`docs/dev-workflow.md`](docs/dev-workflow.md) — PR branch deploy/test/debug loop: deploy labels, skip-tests, live MCP testing, log access.
- [`docs/cli-assets.md`](docs/cli-assets.md) — Naming/lifecycle for CLI-distributed Claude assets, plus a domain-agnostic-guidance principle (Conductor spans engineering/marketing/knowledge, not just GitHub). **Read before adding, renaming, or editing any asset under `conductor-tools/assets/claude/`.**
- [`docs/testing-guidelines.md`](docs/testing-guidelines.md) — Backend test conventions: pick the lightest context, share one Postgres container, protect the context cache. **Read before adding a `@SpringBootTest`/Testcontainers test.**
