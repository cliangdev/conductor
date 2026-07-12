# Conductor

Team PRD collaboration platform. Claude Code generates PRDs; this app handles review, approval, and team workflow.

## Maintaining This File

Keep this file under 200 lines. Review changes to it like code. Rules for what belongs here:

- **Include**: build commands, directory layout, monorepo structure, coding conventions, team norms, pointers to deeper docs
- **Exclude**: procedural workflows, release checklists, ops runbooks → put those in `.claude/skills/` or `docs/`
- **Don't** encode enforcement rules ("never do X") here — use hooks or permission settings instead
- Any section growing past ~15 lines should move to a dedicated doc or skill with a pointer here

## Project Structure

```
conductor/
├── conductor-backend/     # Spring Boot 4.1.0, Java 21, Maven
├── conductor-frontend/    # Next.js 16, TypeScript, Tailwind, shadcn/ui
└── conductor-tools/       # @cliangdev/conductor — CLI + MCP server (single npm package)
```

## conductor-backend

Spring Boot REST API. OpenAPI-first — see [`docs/api-guidelines.md`](docs/api-guidelines.md).

```
src/main/java/com/conductor/
├── config/        # Spring Security, GCP storage, RestTemplate
├── controller/    # REST controllers (implement generated interfaces)
├── dto/           # Generated request/response DTOs
├── entity/        # JPA entities
├── exception/     # GlobalExceptionHandler, typed exceptions (RFC 7807)
├── repository/    # Spring Data JPA repositories
├── security/      # JWT filter, API key filter, Firebase token verification
└── service/       # Business logic

src/main/resources/
├── openapi.yaml               # Source of truth for all API endpoints
└── db/migration/V*.sql        # Flyway migrations (PostgreSQL 15)
```

**Auth**: Firebase Google OAuth → app JWT (HTTP-only cookie). API key auth also supported for CLI.

**Key env vars**: `FIREBASE_PROJECT_ID`, `FIREBASE_SERVICE_ACCOUNT_KEY`, `JWT_SECRET`, `DATABASE_URL`, `RESEND_API_KEY`, `GCP_STORAGE_BUCKET_NAME`, `GCP_SERVICE_ACCOUNT_KEY`, `FRONTEND_URL`

**Run**: `mvn spring-boot:run` · **Test**: `mvn test`

## conductor-frontend

Next.js 16 App Router. Auth via `AuthContext` (Firebase JS SDK + app JWT). Project scope via `ProjectContext`.

```
src/
├── app/
│   ├── app/projects/[projectId]/
│   │   ├── [area]/[noun]/     # Work Item list, workflow-scoped (e.g. engineering/issues)
│   │   │   └── [displayId]/   # Work Item detail: doc viewer + comments + review panel
│   │   ├── workflows/         # Workflow list + lifecycle (statechart) / automation (YAML) editors
│   │   └── members/           # Member management
│   ├── invites/[token]/accept/
│   └── login/
├── components/
│   ├── comments/    # CommentableDocument, CommentThread, NewCommentForm
│   ├── issues/      # StatusDropdown
│   ├── markdown/    # MarkdownRenderer (react-markdown + remark-gfm + rehype-highlight)
│   ├── members/     # MemberRow
│   ├── reviews/     # ReviewSubmissionForm, ReviewersSummaryPanel
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
├── commands/    # CLI commands: mcp, start, stop, status, dashboard, login, init, config, doctor
├── daemon/      # watcher.ts — chokidar file watcher, 500ms debounce
├── lib/         # API client, config loader
└── mcp/         # MCP server (stdio): work items, documents, workflows, comments, integrations tools
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
- [`docs/mcp-tool-guidelines.md`](docs/mcp-tool-guidelines.md) — MCP tool design principles: context budget, action–verify pattern, dispatch–status pattern, checklist. **Read before creating or updating any MCP tool.**
- [`docs/dev-workflow.md`](docs/dev-workflow.md) — PR branch deploy/test/debug loop: deploy labels, skip-tests, live MCP testing, log access.
- [`docs/cli-assets.md`](docs/cli-assets.md) — Naming conventions and lifecycle for CLI-distributed Claude assets (commands, skills, agents). **Read before adding, renaming, or removing any asset under `conductor-tools/assets/claude/`.**
- [`docs/testing-guidelines.md`](docs/testing-guidelines.md) — Backend test conventions: pick the lightest context, share one Postgres container, protect the context cache. **Read before adding a `@SpringBootTest`/Testcontainers test.**
