# Knowledge Center

The Knowledge Center is an LLM-maintained wiki for a project: an AI "librarian" reads inbound material
(Work Item status changes, merged GitHub PRs, manual notes, anything you submit) and files it into a
bundle of versioned Markdown pages with YAML frontmatter. Nobody hand-writes wiki pages — the librarian
creates and edits them; humans and other agents read them.

## Table of contents

- [Concept](#concept)
- [The ingestion envelope](#the-ingestion-envelope)
- [Producers](#producers)
- [The pipeline](#the-pipeline)
- [Page model](#page-model)
- [System workflows](#system-workflows)
- [MCP tools](#mcp-tools)
- [REST endpoints](#rest-endpoints)
- [Roadmap](#roadmap)

---

## Concept

Three layers, each with a different mutability:

| Layer | Mutability | What it is |
|---|---|---|
| Sources inbox (`knowledge_sources`) | Immutable, append-only | Raw inbound material — a Work Item status change, a merged PR, a manual note. Never edited, only claimed and marked processed. |
| Wiki pages (`knowledge_pages`) | Agent-owned, versioned | Markdown + YAML frontmatter, one file per page, `path` is identity. The librarian creates and edits these; the format is referred to in code as **OKF** (Markdown body + YAML frontmatter — no separate spec, just the convention this codebase follows). |
| `_schema.md` | Agent-authored style guide | A wiki page like any other, but read by the librarian as its own instructions: frontmatter contract, page-type taxonomy, path layout, create-vs-edit heuristics. Seeded on enable, then it's just another page an operator or the librarian can evolve. |

This is deliberately **not RAG** (retrieval over raw source chunks at query time). Sources are filed once,
at ingestion time, into durable, editable pages that get more accurate as the librarian revises them —
the wiki *compounds*: later sources correct or extend earlier pages instead of just piling up more chunks
to search over. Retrieval (`search_knowledge`, `read_knowledge_pages`) reads the compounded pages, not the
raw inbox.

---

## The ingestion envelope

Every unit of inbound material — regardless of producer — is a `KnowledgeSubmission` with the same shape:

| Field | Required | Description |
|---|---|---|
| `projectId` | yes | Target project. |
| `sourceType` | yes | Free-form producer tag, e.g. `conductor.work_item.status_changed`, `github.pr_merged`, `codebase.snapshot`. |
| `sourceRef` | one of `payload`/`sourceRef` | By-reference pointer (e.g. `github:owner/repo#123`) the librarian or a later adapter resolves. |
| `payload` | one of `payload`/`sourceRef` | By-value inline content (e.g. a JSON blob of the event). |
| `title`, `contentType`, `occurredAt`, `metadata` | no | Descriptive metadata carried through to the librarian's read. |
| `dedupKey` | no | Explicit idempotency key. When omitted, derived server-side from `projectId`+`sourceType`+`sourceRef`+`occurredAt` (SHA-256). |
| `origin` | server-derived | `{kind, id}` — who/what submitted it (`USER`, `API_KEY`, `EVENT_TAP`, `GITHUB_CONNECTOR`). Never accepted from the client; always derived from the caller's auth or the adapter's identity. |

**Idempotency.** Submission is claim-or-return on `(projectId, dedupKey)`: the first caller for a key
inserts the row (`ACCEPTED`); any later caller with the same key gets back the original row's id
(`DUPLICATE`) without inserting again. Safe to retry a submission blindly.

**64KB offload.** A `payload` over 64KB is uploaded to GCS (`StorageService`) and the row keeps only a
`payload_uri`; the `payload` column is left null. Reads resolve it transparently — `read_knowledge_sources`
downloads and inlines it, but a plain inbox browse (`listSources`) never triggers a download per row.

---

## Producers

All four are gated on the `knowledge_enabled` project setting (`project_settings.knowledge_enabled`,
default `false`) — nothing is ingested until it's turned on. As of this phase there's no dedicated
frontend settings page for it; toggle it via `PATCH /api/v1/projects/{projectId}/settings` with
`{"knowledgeEnabled": true}`.

| Producer | `sourceType` | Trigger |
|---|---|---|
| REST `POST /knowledge/sources` | caller-supplied | Any authenticated caller (user or project API key) submits directly. |
| MCP `submit_knowledge_source` | caller-supplied | Same endpoint, called from Claude Code or a workflow's `claude-code` step. |
| Work Item status-change event tap (`KnowledgeEventTap`) | `conductor.work_item.status_changed` | Fourth consumer wired into `NotificationDispatcher.dispatch`, alongside workflow/lifecycle triggers. Its own try/catch — an ingestion failure never blocks notification delivery or trigger evaluation. |
| GitHub `pr_merged` adapter (`GitHubConnector`) | `github.pr_merged` | On a merged PR webhook, submits it as a source regardless of whether it references a Conductor Work Item — this is about the codebase, not one issue. |

---

## The pipeline

`KnowledgeIngestScheduler` polls every 30s:

1. **Dispatch.** For each project with a due `PENDING` source (`nextAttemptAt` null or past) and
   `knowledge_enabled`, claim up to 10 oldest sources into `PROCESSING` (`REQUIRES_NEW` transaction), then
   fire a `knowledge-librarian` run via `LibrarianDispatchService` — a `workflow_dispatch` trigger carrying
   `sourceIds` (comma-joined) and `projectId` as top-level event-payload fields (`${{ event.sourceIds }}`),
   not `workflow_dispatch` `inputs` (see [`docs/workflows.md`](workflows.md#outputs-and-interpolation) —
   `event.FIELD` resolves any trigger's stored payload).
2. **One active knowledge run per project.** Before claiming, the scheduler checks for a non-terminal run
   (`PENDING`/`PENDING_LOCAL_PICKUP`/`RUNNING`) of either `knowledge-librarian` or `knowledge-bootstrap` and
   skips dispatch if one is in flight — the librarian and the bootstrap seed job never race each other or
   themselves.
3. **Librarian files the batch.** The dispatched `claude-code` step reads `_schema.md` and `index.md` for
   orientation, reads the batch's sources, drafts page content, and writes every resulting page in **one**
   `write_knowledge_pages` call passing `sourceIds` — which atomically marks the batch `PROCESSED` in the
   same transaction as the page writes (`KnowledgeSourceRepository.markProcessed`, `flushAutomatically` +
   `clearAutomatically`, so a crash between the write and the mark can never happen). If no source in the
   batch warrants a wiki change, the librarian still calls `write_knowledge_pages` with `writes: []` and
   `sourceIds` set to the full batch — an explicit "no wiki change needed" ack, so the batch is marked
   `PROCESSED` instead of rotting through the stale-processing sweep into `DEAD`.
4. **Sweep.** Every tick, any source still `PROCESSING` whose run is missing, terminally
   failed/cancelled/timed-out, or has simply run longer than 30 minutes is resurrected: attempts++, back to
   `PENDING` with exponential backoff (`60s * 2^attempts`), or — at 5 attempts — `DEAD` with an error
   message. If the librarian workflow isn't provisioned yet (a race with the enable transaction), the claimed
   batch is released straight back to `PENDING` instead of being dispatched into a void.

Source lifecycle: `PENDING → PROCESSING → PROCESSED` (success) or `PENDING → PROCESSING → PENDING`
(retried, backoff) `→ … → DEAD` (exhausted). A `FAILED` status is defined on the enum but not currently
produced by this pipeline — reserved for a future explicit-failure path.

---

## Page model

- **`path` is identity.** Lowercase, hyphenated, `.md`-suffixed (`^[a-z0-9_][a-z0-9_/.-]*\.md$`), unique per
  project. `..` segments are rejected; `index.md` and `log.md` are reserved (see below).
- **Frontmatter contract.** Every page is Markdown with a leading `---`/`---` YAML block. `type` is the only
  *required* field — a page with none is rejected (422). `title`, `description`, `resource`, `tags`,
  `timestamp`, `confidence`, `sources` are recommended conventions (defined in `_schema.md`, not enforced by
  the parser); any other key round-trips verbatim so page-type-specific fields survive. The taxonomy ships
  with 8 types: `person`, `project`, `decision`, `meeting`, `metric`, `feature`, `architecture`,
  `integration`.
- **Versioning + optimistic concurrency.** Every page has an integer `version`, starting at 1. A
  `write_knowledge_pages`/`batch-write` call supplies `baseVersion` per write: `null` means "I believe this
  path doesn't exist yet"; any other value must match the current stored version exactly. **The whole batch
  is all-or-nothing** — one stale write aborts every write in the call (nothing is persisted) and raises a
  409 carrying every conflicting path's `{path, currentVersion, currentContent}` (`currentContent` null when
  the path has no live page at all). The caller re-reads, merges, and retries once with the fresh version.
- **Revisions + provenance.** Every create/update/delete writes a full-content `knowledge_page_revisions`
  row (`CREATE`/`UPDATE`/`DELETE`) with an `Actor` (`kind`, `id`, `workflowRunId`) and, if the write's
  `sourceIds` were supplied, a `knowledge_revision_sources` link per source — so "what fed this edit" is
  queryable independent of the optional `sources` frontmatter field.
- **Link graph.** On every write, outgoing Markdown links (`[text](/dir/page.md)` or relative) are
  re-extracted from the body into `knowledge_links`, resolved against live pages where possible. A link to a
  page that doesn't exist yet is stored dangling and **re-resolved automatically** the moment that path is
  created (`resolveDangling`); deleting a page unresolves links that pointed at it.
- **Virtual pages.** `index.md` (every live page grouped by directory) and `log.md` (last 100 revisions
  grouped by day, with source refs) are generated on read, never stored — the librarian's two orientation
  reads. Both report `version: 0`.
- **Reserved paths.** `index.md`, `log.md` cannot be written to (400). A leading underscore is otherwise a
  normal path character (`_schema.md` is a real, editable page, not special-cased beyond being seeded).

---

## System workflows

Two automation workflows are provisioned automatically — the first time a project's `knowledge_enabled`
flips `false → true` — by `KnowledgeWorkflowProvisioner`, from the YAML templates in
`conductor-backend/src/main/resources/knowledge/`. They're identified purely by reserved workflow name (no
schema-level "system" flag); re-enabling is idempotent (upsert-if-missing). See
[`docs/workflows.md`](workflows.md#system-managed-workflows) for how they fit the general workflow model.

| Workflow | Trigger | Purpose |
|---|---|---|
| `knowledge-librarian` | `workflow_dispatch`, fired programmatically by `LibrarianDispatchService` — never by a human | Files one batch of claimed sources into pages. `concurrency: single`. |
| `knowledge-bootstrap` | `workflow_dispatch` with a required `repo` input (`owner/repo`) | Operator-triggered once, to seed the wiki (`engineering/architecture/*.md`, `product/features/*.md`) from an existing codebase by cloning and reading it. |

Both run a `claude-code` step on `runs-on: cloud-run` with `conductor_mcp: true`. **Operator prerequisites**
before either can run:

- The project's **Claude Code (subscription)** credential (**Integrations → Google Cloud**) — required for
  any `cloud-run` `claude-code` step.
- An active **project API key** (**Settings → API Keys**) — required for `conductor_mcp: true` on
  `cloud-run`.
- `knowledge-bootstrap` only: a **`GITHUB_TOKEN`** workflow secret (**Settings → Workflows → Secrets**) with
  read access to the target repo, for cloning a private repo. Unset works fine for a public repo (the token
  segment interpolates to empty and `git clone` proceeds unauthenticated).

---

## MCP tools

Five tools in `conductor-tools` (`src/mcp/tools/knowledge.ts`), used by the librarian/bootstrap workflows
and available to any Claude Code session with the Conductor MCP server configured:

| Tool | Purpose |
|---|---|
| `submit_knowledge_source` | Push one source into the inbox. Idempotent on `dedupKey`; `DUPLICATE` status is not an error. |
| `read_knowledge_sources` | Fetch inbox sources by id, with offloaded payloads resolved inline. |
| `search_knowledge` | Full-text search over pages — path, type, title, description, snippet, rank. Orientation before reading. |
| `read_knowledge_pages` | Fetch full page content by path. `["index.md"]`/`["log.md"]` return the virtual orientation pages. Returned `version` feeds `baseVersion` on the next write. |
| `write_knowledge_pages` | Atomic batch create/update/delete; `writes` may be empty when `sourceIds` is set, to ack a batch that needs no page changes. A stale write returns a structured `{conflict: true, conflicts: [...]}` result instead of throwing, per [MCP tool guidelines](mcp-tool-guidelines.md) — merge and retry once. |

---

## REST endpoints

All under `/api/v1/projects/{projectId}/knowledge/`, accepting both a user session (project membership via
`ProjectSecurityService`) and a project-scoped API key:

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/sources` | Submit a source. `202` with `{sourceId, status}`. |
| `GET` | `/sources` | List by `status` (default `PENDING`), or multi-get via `ids` (mutually exclusive; `ids` wins). |
| `POST` | `/pages/batch-write` | Atomic create/update/delete batch. `200` on success; `409` with a `conflicts` extension on a concurrency race; `422` on malformed frontmatter. |
| `GET` | `/pages?paths=` | Multi-get full page content by comma-separated paths. Unknown/deleted paths silently omitted. |
| `GET` | `/index` | The generated virtual `index.md`. |
| `GET` | `/search?q=` | Full-text search; optional `type`, `pathPrefix`, `limit` (default 20). |
| `GET` | `/revisions?path=` | Revision history for one page, newest first, with actor + source provenance. |

---

## Roadmap

Deliberately deferred out of this phase:

- **Lint workflow** — a scheduled pass that checks the wiki against `_schema.md`'s own conventions (broken
  links, missing recommended frontmatter, orphaned pages) and files findings back into the inbox.
- **Human editing** — the frontend wiki browser is read-only; writing a page today means the librarian, or
  a direct API/MCP call, not an in-app editor.
- **Review-gated edits** — a human-approval step before a librarian write lands, for higher-stakes pages.
- **Embeddings / semantic search** — search is Postgres full-text (`tsvector`/GIN) only; no vector index.
- **Pub/Sub-driven ingestion** — the scheduler is poll-based (30s); no push-triggered dispatch yet.
