# Conductor

Team PRD collaboration platform. Claude Code generates PRDs; this app handles review, approval, and team workflow.

## Project Structure

```
conductor/
├── conductor-backend/     # Spring Boot 3.3.4, Java 21, Maven
├── conductor-frontend/    # Next.js 14, TypeScript, Tailwind, shadcn/ui
└── conductor-tools/
    ├── cli/               # @conductor/cli — npm package, Commander.js
    └── mcp/               # @conductor/mcp — MCP server, stdio transport
```

## conductor-backend

Spring Boot REST API. OpenAPI-first: edit `openapi.yaml`, run `mvn generate-sources`, then implement.

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

Next.js 14 App Router. Auth via `AuthContext` (Firebase JS SDK + app JWT). Project scope via `ProjectContext`.

```
src/
├── app/
│   ├── app/projects/[projectId]/
│   │   ├── issues/            # Issue list page
│   │   │   └── [issueId]/     # Issue detail: PRD viewer + comments + review panel
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

## conductor-tools/cli

`@conductor/cli` — `conductor` command for issue/doc management and local sync daemon.

```
src/
├── commands/    # issue, doc, start, stop, login, init, status, doctor
├── daemon/      # watcher.ts — chokidar file watcher, 500ms debounce
└── lib/         # API client, config loader
```

Local files at `~/.conductor/{projectId}/issues/**`. Offline queue at `~/.conductor/sync-queue.json`.

**Build**: `npm run build` · **Test**: `npx vitest`

### Releasing the CLI

The CLI publishes to npm automatically on push to `main` whenever `conductor-tools/package.json` changes. The `Release CLI` workflow (`.github/workflows/release-cli.yml`) is idempotent: it reads the version from `package.json`, skips if `cli-vX.Y.Z` already exists, otherwise runs `npm ci → test → build → publish → tag → gh release create`.

**Per-release workflow:**

1. **On your feature branch, bump the version** in the same commit set as your changes:
   ```bash
   cd conductor-tools && npm version <patch|minor|major> --no-git-tag-version
   ```
   This edits `package.json` and `package-lock.json`. Stage and commit alongside your feature changes.
   → Next: open the PR.

2. **Open and merge the PR.** The `version-bump-check` job in `tools.yml` fails the PR if CLI source under `src/`, `assets/`, or `scripts/` changed without a `package.json` version bump — so you'll know before merge if you forgot.
   → Next: wait for CI to go green and merge via the GitHub UI.

3. **The `Release CLI` workflow auto-fires** on the merge commit (path filter: `conductor-tools/package.json`).
   → Next: watch `Actions → Release CLI → <run>`. ~2 minutes to npm.

4. **Verify the release:**
   ```bash
   npm install -g @cliangdev/conductor@latest
   conductor --version    # → matches the bump
   ```
   → Next: in a target project, `conductor init` to refresh `~/.claude/` skills.

**Recovery cases:**

- **Forgot the version bump (PR already merged)**: open a one-line follow-up PR bumping `conductor-tools/package.json`. Same flow.
- **Workflow failed mid-release**: re-run via `Actions → Release CLI → <run> → Re-run failed jobs`. Idempotent — skips publish if the tag already landed, retries from the failure point otherwise.
- **Two PRs raced on the same version**: the second `npm publish` fails with HTTP 403 (`version already exists`). Bump again in a new PR.
- **Manual fallback** (workflow broken):
  ```bash
  cd conductor-tools && npm publish --access public
  git tag cli-vX.Y.Z && git push origin cli-vX.Y.Z
  gh release create cli-vX.Y.Z --generate-notes --title "CLI vX.Y.Z"
  ```
- **Need to release a version already in `main` but not yet tagged** (e.g. recovery from a failed manual run): `Actions → Release CLI → Run workflow` (workflow_dispatch). Reads the current `package.json` and ships it.

## conductor-tools/mcp

`@conductor/mcp` — MCP server for Claude Code integration (stdio transport, 8 tools).

```
src/
├── tools/     # issues.ts, documents.ts
├── api.ts     # authenticated HTTP client
├── config.ts  # reads ~/.conductor/config.json
├── files.ts   # local file read/write
└── queue.ts   # offline sync queue
```

**Run**: `node dist/index.js` · **Config**: `~/.conductor/config.json`

## Data Model (key tables)

`users` → `project_members` (ADMIN/CREATOR/REVIEWER) → `projects`  
`issues` → `documents` (GCP-backed, signed URLs)  
`issue_reviewers` → `reviews` (APPROVED/CHANGES_REQUESTED/COMMENTED)  
`comments` + `comment_replies` (line-level or selection-based anchors)  
`project_settings` (Discord webhook URL)  
`invites`, `api_keys`

## Fetching Cloud Run Logs

Two options:

**Option 1 — `scripts/logs.sh`** (wraps gcloud, requires `CONDUCTOR_GCP_PROJECT`):
```bash
export CONDUCTOR_GCP_PROJECT=<your-gcp-project>
./scripts/logs.sh                        # backend logs, last 50 lines
./scripts/logs.sh frontend               # frontend logs, last 50 lines
./scripts/logs.sh backend --lines 200    # last 200 lines
./scripts/logs.sh backend --since 1h     # last 1 hour
```

**Option 2 — `gcloud` directly** (useful with a named configuration or alias):
```bash
gcloud --configuration=<config> --project=<project> logging read \
  "resource.type=cloud_run_revision AND resource.labels.service_name=conductor-backend" \
  --limit=50 --format=json | python3 -c "
import json, sys
for l in reversed(json.load(sys.stdin)):
    ts = l.get('timestamp','')[:19]
    sev = l.get('severity','')
    msg = l.get('textPayload') or l.get('jsonPayload',{}).get('message','') or l.get('httpRequest',{}).get('requestUrl','')
    if msg: print(f'{ts} [{sev}] {msg}')
"
```

Set up a shell alias for your deployment (see `scripts/gcloud-alias-example.sh`) so you don't have to repeat the flags. Requires `gcloud` CLI authenticated with access to the target project.

## API Workflow

All backend API changes:
1. Update `conductor-backend/src/main/resources/openapi.yaml`
2. `mvn generate-sources` (generates controller interfaces + DTOs)
3. Implement the generated interface in a `@RestController`
