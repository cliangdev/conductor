# Conductor API Guidelines

Authoritative reference for **creating or updating any backend API**. Read this before touching
`openapi.yaml` / `openapi-internal.yaml` or adding a controller. Pair it with `CLAUDE.md` (API Workflow)
and the connector design principles.

---

## 1. OpenAPI-first — the spec is the source of truth

Every endpoint is defined in a spec first, then implemented against the generated interface. Never
hand-roll a controller mapping that isn't backed by a spec operation (the SSE stream is the one
documented exception — see §5).

1. Edit the spec (`conductor-backend/src/main/resources/openapi.yaml` or `openapi-internal.yaml`).
2. `mvn generate-sources` → regenerates interfaces (`com.conductor.generated[.internal].api`) + DTOs
   (`…​.model`).
3. Implement the generated interface in a `@RestController`. Don't edit generated code.

The Maven `openapi-generator-maven-plugin` has three executions (`external`, `internal`, `v2`) — one
per spec (`openapi.yaml`, `openapi-internal.yaml`, `openapi-v2.yaml`).

---

## 2. External vs internal APIs — separated at every level

Conductor has **two distinct API surfaces**. Keep them separated by spec, package, prefix, version,
and Swagger group. Decide which surface an endpoint belongs to *before* writing it.

| | External API | Internal control-plane |
|---|---|---|
| Audience | frontend, CLI, third parties | worker VM, daemon, `DockerStepExecutor` |
| Spec | `openapi.yaml` | `openapi-internal.yaml` |
| Generated pkg | `com.conductor.generated.api` / `.model` | `com.conductor.generated.internal.api` / `.model` |
| Controller pkg | `com.conductor.controller` (+ `integration.connector.*`) | `com.conductor.internal` |
| Base prefix | `/api/v1` | `/internal/v1` |
| Auth | app JWT (cookie) / API key | per-run bearer token (`RunTokenService`) |
| Swagger group | `/api-docs/external` | `/api-docs/internal` |

**The controller's package is the single source of truth for which surface (and prefix) it gets** —
see `ApiPathConfig`. There is no annotation to toggle; move the class to change its surface.

**A shared service across the boundary is fine** (e.g. `WorkflowRunLogBroker` backs both the external
SSE controller and the internal callback controller). What stays separated is the *API surface*, not
the internal implementation.

**Default to external.** Only use the internal surface for machine-to-machine control-plane traffic that
is never called by a browser/CLI and is authenticated by something other than the app JWT.

**The v2 external surface (`/api/v2`)** is the canonical Work Item API (`openapi-v2.yaml` →
`com.conductor.generated.v2.*`, controllers in `com.conductor.v2.controller`, Swagger group `v2`). It
superseded the retired v1 `issues` surface (see §4). New Work Item endpoints go here; the pre-v2 `openapi.yaml`
external spec now covers only the non-Work-Item surface (projects, members, docs, workflows, agents, etc.).

---

## 3. The base prefix is centralized — never hand-write it

`com.conductor.config.ApiPathConfig` applies `/api/v1` and `/internal/v1` via `addPathPrefix`, scoped
by controller package. Therefore:

- **Controllers map at BARE paths.** Write `@GetMapping("/projects/{id}")`, never `"/api/v1/projects/{id}"`.
  A class-level `@RequestMapping("/api/v1")` is a bug — it double-prefixes or drifts.
- A controller added with no base mapping is automatically served under the right prefix. A missing
  prefix is structurally impossible (this class exists because a forgotten prefix shipped a prod 500 —
  see issue #184).
- Sub-bases are bare too: `@RequestMapping("/auth")`, `@RequestMapping("/local-files")`.
- Specs declare the base once via `servers.url` (`/api/v1`, `/internal/v1`); method paths in the spec
  are bare. springdoc and actuator are untouched (they aren't `com.conductor` `@RestController`s).

---

## 4. Versioning

- The version lives in the prefix (`v1`), centralized in `ApiPathConfig` — not in each path.
- Both surfaces are versioned (`/api/v1`, `/internal/v1`).
- Make **backward-compatible** changes in place (add optional fields, add endpoints). Only introduce
  a new prefix (a new predicate) for a genuine breaking change, and run both during migration. The
  `issues` → `work-items` rename did exactly this: `/api/v2` ran alongside a deprecated v1 shim until
  callers migrated, then the v1 issue surface (paths, schemas, `com.conductor.legacy` controllers) was
  deleted. Don't reintroduce v1 issue endpoints. (The `legacy-v1-deprecation.md` tracking doc has been
  removed now that the migration is complete — this is the authoritative note.)
- The internal surface has no external consumers, so it can be re-versioned by updating all in-repo
  callers (backend `DockerStepExecutor`, `conductor-worker`, `conductor-tools`) in the same PR.

---

## 5. REST conventions

- **Resource-oriented, plural nouns:** `/projects/{projectId}/work-items/{workItemId}`. No verbs in paths
  (`/getWorkItem` ✗). Express actions with HTTP methods, or a sub-resource for non-CRUD state changes
  (`POST /work-items/{id}/reviews`).
- **HTTP methods:** `GET` (safe, no side effects), `POST` (create / non-idempotent action),
  `PUT`/`PATCH` (full / partial update), `DELETE` (remove). `PATCH` for partial edits is the norm here.
- **Status codes:** `200` ok, `201` created (set `Location` when useful), `204` no content,
  `400` validation, `401` unauthenticated, `403` authorized-but-forbidden, `404` not found,
  `409` conflict, `422` semantic validation. Don't return `200` with an error body.
- **Errors are RFC 7807** `application/problem+json`, produced centrally by `GlobalExceptionHandler` —
  throw a typed exception (e.g. `ForbiddenException`, `EntityNotFoundException`), don't assemble error
  bodies in controllers.
- **Idempotency:** `PUT`/`DELETE` and worker callbacks should be safe to retry (e.g. outputs recording
  is a no-op for an unknown job). Document idempotent behavior in the spec response description.
- **Naming:** `camelCase` JSON properties, `kebab-case` multi-word path segments
  (`/log-chunk`, `/local-files`).
- **Collections:** prefer pagination for unbounded lists; keep query params for filtering/sorting, not
  for selecting actions.

---

## 6. Security & access control

- **Every external endpoint that touches project data must check membership** via
  `ProjectSecurityService` (per `CLAUDE.md` — `project_members` is the only access gate). Don't trust a
  `projectId` in the path without verifying the caller belongs to it.
- Public/unauthenticated routes are explicitly allow-listed in `SecurityConfig` — add new ones there
  consciously, with the prefix (`/api/v1/...`, `/internal/**`).
- Internal endpoints validate their own per-run token (`RunTokenService`); `/internal/**` is
  `permitAll` at the filter level precisely because auth is per-call.

---

## 7. Checklist for adding / updating an endpoint

- [ ] Decided external vs internal (§2); editing the right spec.
- [ ] Spec operation has `operationId`, `tags`, `security`, typed request/response schemas, and the
      full set of status codes (§5).
- [ ] `mvn generate-sources` run; controller implements the generated interface.
- [ ] Controller is in the correct package; mapping is **bare** (no hand-written prefix) (§3).
- [ ] Membership/authorization checked (§6).
- [ ] Errors via typed exceptions → `GlobalExceptionHandler` (§5).
- [ ] Tests: `@WebMvcTest` slice or E2E asserting the **prefixed** path; `mvn test` green.
- [ ] If the internal API changed, all in-repo callers + their tests updated in the same PR, and the
      CLI version bumped if `conductor-tools/src/**` changed.
- [ ] `docs/workflows.md` updated if workflow-related; this guide updated if a convention changed.
