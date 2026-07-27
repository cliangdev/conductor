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
- [Metrics digests](#metrics-digests)
- [Page model](#page-model)
- [System workflows](#system-workflows)
- [Domains](#domains)
- [Retention](#retention)
- [MCP tools](#mcp-tools)
- [REST endpoints](#rest-endpoints)
- [Frontend surfaces](#frontend-surfaces)
- [Roadmap](#roadmap)

---

## Concept

Three layers, each with a different mutability:

| Layer | Mutability | What it is |
|---|---|---|
| Sources inbox (`knowledge_sources`) | Immutable, append-only | Raw inbound material — a Work Item status change, a merged PR, a manual note. Never edited, only claimed, marked processed, and eventually [retired by retention](#retention) once it's served its purpose. |
| Wiki pages (`knowledge_pages`) | Agent-owned, versioned | Markdown + YAML frontmatter, one file per page, `path` is identity. The librarian creates and edits these; the format is referred to in code as **OKF** (Markdown body + YAML frontmatter — no separate spec, just the convention this codebase follows). |
| `_schema.md` | Agent-authored style guide | A wiki page like any other, but read by the librarian as its own instructions: frontmatter contract, page-type taxonomy, path layout, per-type body templates (stable section structure per type; `architecture` pages are diagram-first with a C4-style Mermaid flowchart), create-vs-edit heuristics. Seeded on enable, then it's just another page an operator or the librarian can evolve. |

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
| `domain` | no | Explicit [domain](#domains) slug to route into (validated against the ACTIVE registry; unknown/inactive is a 400). Omitted lets `KnowledgeDomainResolver` route by `sourceType` glob pattern instead; still unmatched falls to the null/generalist lane. Resolved once at submit time and stamped onto the row — never re-resolved later. |

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

How long a lane accumulates before it dispatches is `project_settings.knowledge_ingest_interval_minutes`
(default **60**, i.e. hourly) — same endpoint, `{"knowledgeIngestIntervalMinutes": 15}` (1–1440 minutes).
Configurable from the frontend at **Manage → Ingest cadence** (`knowledge/manage`, admin-only; see
[Frontend surfaces](#frontend-surfaces)).

| Producer | `sourceType` | Trigger |
|---|---|---|
| REST `POST /knowledge/sources` | caller-supplied | Any authenticated caller (user, project API key, or a run-scoped workflow MCP token) submits directly. |
| MCP `submit_knowledge_source` | caller-supplied | Same endpoint, called from Claude Code or a workflow's `claude-code` step. |
| Work Item status-change event tap (`KnowledgeEventTap`) | `conductor.work_item.status_changed` | Fourth consumer wired into `NotificationDispatcher.dispatch`, alongside workflow/lifecycle triggers. Its own try/catch — an ingestion failure never blocks notification delivery or trigger evaluation. |
| GitHub `pr_merged` adapter (`GitHubConnector`) | `github.pr_merged` | On a merged PR webhook, submits it as a source regardless of whether it references a Conductor Work Item — this is about the codebase, not one issue. |

---

## The pipeline

```mermaid
flowchart LR
    Producers["Producers<br/>MCP · event tap · UI"]
    Inbox[("knowledge_sources<br/>(inbox, domain-stamped)")]
    Sched["KnowledgeIngestScheduler<br/>30s poll · per-lane claim ≤10 · sweep"]
    Librarian["knowledge-librarian run<br/>(agent: resolved per lane)"]
    Pages[("knowledge_pages +<br/>revisions · links")]

    Producers -- "submit (PENDING, domain resolved)" --> Inbox
    Sched -- "claim lane → PROCESSING" --> Inbox
    Sched -- "dispatch batch per lane" --> Librarian
    Librarian -- "write_knowledge_pages<br/>(atomically marks PROCESSED)" --> Pages
    Sched -. "stale/failed → PENDING (backoff)<br/>5 attempts → DEAD" .-> Inbox
```

`KnowledgeIngestScheduler` polls every 30s, per **lane** — a lane is a [domain](#domains) slug, or `null`
for the generalist/unclassified lane; the concurrency unit is `(project, lane)`, not the whole project:

1. **Dispatch.** For each project with `knowledge_enabled` and any due `PENDING` source (`nextAttemptAt`
   null or past), the scheduler enumerates every lane with due work and, for each lane not already busy,
   claims up to 10 oldest sources *in that lane* into `PROCESSING` (`REQUIRES_NEW` transaction), then fires
   a `knowledge-librarian` run via `LibrarianDispatchService` — a `workflow_dispatch` trigger carrying
   `sourceIds` (comma-joined), `projectId`, `agentSlug`, and `domain` (`""` for the null lane) as top-level
   event-payload fields (`${{ event.sourceIds }}` etc.), not `workflow_dispatch` `inputs` (see
   [`docs/workflows.md`](workflows.md#outputs-and-interpolation) — `event.FIELD` resolves any trigger's
   stored payload). Multiple lanes can dispatch in the same tick — an in-flight engineering-domain batch
   never blocks a product-domain batch.

   The 30s tick is only the scheduler's *poll* granularity, not how soon a lane actually dispatches:
   `KnowledgeIngestionService` stamps a source's `nextAttemptAt` at ingest time based on the project's
   `knowledgeIngestIntervalMinutes` (default 60). A source landing in an otherwise-idle lane (no other
   `PENDING`/`PROCESSING` source there yet) is stamped `now + interval` rather than left immediately due
   — that's what turns ingestion into "accumulate for up to the interval, then dispatch as one batch"
   instead of firing on the very next tick. A source arriving in a lane that's already accumulating
   inherits that same scheduled time instead of getting its own, so it rides along in the same batch. See
   [Producers](#producers) for how to configure the interval.
2. **Per-lane busy check, project-wide bootstrap block.** A lane with any `PROCESSING` source is treated
   as busy and skipped this tick without affecting any other lane — best-effort per-lane serialization,
   not a hard guarantee: the busy-check and the claim are separate queries (a TOCTOU race is possible
   across scheduler instances/ticks), and a lane briefly reads as free again once a batch is marked
   `PROCESSED`, before its librarian run has actually terminated. Concurrent same-lane writers are
   backstopped by `KnowledgePageService`'s optimistic page versioning and the retry-once conflict
   protocol, not by this check — a non-terminal `knowledge-librarian` run no longer blocks the whole
   project the way it once did. The one project-wide block is `knowledge-bootstrap`: while it has a
   non-terminal run, no lane dispatches — it writes broadly across the wiki in one large
   operator-triggered run.
3. **Agent resolution.** `LibrarianDispatchService` resolves `agentSlug` per dispatch: the lane's domain
   row's `owningAgentSlug` if one is assigned and that agent still exists, else the generalist
   `knowledge-librarian` — a deleted specialist demotes its lane back to the generalist on the next
   dispatch rather than stranding it. `knowledge-librarian.yaml`'s `agent: ${{ event.agentSlug }}`
   resolves this per run (see [`docs/workflows.md`](workflows.md#outputs-and-interpolation) — `with.agent`
   now accepts `${{ }}` interpolation, same as `task`/`context`; a fixed literal agent slug in any other
   workflow still works unchanged).
4. **Librarian files the batch.** The dispatched agent step reads `_schema.md` (and, if `Domain` is
   non-empty, that domain's own `<domain>/_schema.md`) and `index.md` for orientation, reads the batch's
   sources, drafts page content, and writes every resulting page in **one** `write_knowledge_pages` call
   passing `sourceIds` — which atomically marks the batch `PROCESSED` in the same transaction as the page
   writes (`KnowledgeSourceRepository.markProcessed`, `flushAutomatically` + `clearAutomatically`, so a
   crash between the write and the mark can never happen). If no source in the batch warrants a wiki
   change, the librarian still calls `write_knowledge_pages` with `writes: []` and `sourceIds` set to the
   full batch — an explicit "no wiki change needed" ack, so the batch is marked `PROCESSED` instead of
   rotting through the stale-processing sweep into `DEAD`.
5. **Sweep.** Every tick, any source still `PROCESSING` whose run is missing, terminally
   failed/cancelled/timed-out, or has simply run longer than 30 minutes is resurrected: attempts++, back to
   `PENDING` with exponential backoff (`60s * 2^attempts`), or — at 5 attempts — `DEAD` with an error
   message. If the librarian workflow isn't provisioned yet, or its stored YAML has drifted from the
   current classpath template (e.g. a project enabled before per-lane `agent: ${{ event.agentSlug }}`
   shipped), dispatch self-heals via `KnowledgeWorkflowProvisioner.provision` (which refreshes drifted
   system-workflow YAML in place) before retrying; if that still fails, the claimed batch is released
   straight back to `PENDING` instead of being dispatched into a stale or missing target.

Source lifecycle: `PENDING → PROCESSING → PROCESSED` (success) or `PENDING → PROCESSING → PENDING`
(retried, backoff) `→ … → DEAD` (exhausted). `DEAD` isn't necessarily final — an admin can reset a
project's dead sources back to `PENDING` via [`POST /sources/retry`](#rest-endpoints) after fixing
the underlying cause.

---

## Metrics digests

A second, parallel producer feeds the same wiki: a connector can declare a scheduled **feed**
(`ingest[]` in its tool-spec JSON — see
[`docs/integrations-adding-a-connector.md`](integrations-adding-a-connector.md#connector-feeds-metrics-digests))
that pulls a metric series on a cadence and, when something material happened, narrates it into a
knowledge source. This is not the source-inbox pipeline above with a different trigger — it's a
distinct pre-pipeline that decides *whether a source should exist at all*, then hands its output to
that exact same inbox → librarian machinery once it does.

```mermaid
flowchart LR
    Sched["ConnectorFeedScheduler<br/>60s poll · claims due feeds"]
    Pull["FeedPullService<br/>pull via SnapshotIngestAdapter<br/>or a connector's own IngestConnector"]
    Digest["MetricsDigestService<br/>aggregate → detect"]
    Row[("connector_feed_digest<br/>PENDING (material) or SKIPPED")]
    Narrator["metrics-narrator run<br/>agent step, zero tools"]
    Submit["DigestSubmissionService<br/>submits the narrative"]
    Inbox[("knowledge_sources<br/>same inbox as any other producer")]

    Sched -- "claim due feed" --> Pull
    Pull -- "one snapshot/window item" --> Digest
    Digest -- "material?" --> Row
    Row -- "PENDING → dispatch" --> Narrator
    Narrator -- "title + prose" --> Submit
    Submit -- "submit()" --> Inbox
    Inbox -. "filed by the librarian,<br/>same as any other source" .-> Pages[("knowledge_pages")]
```

**Four stages**, each owned by a different class:

1. **Aggregate** (`MetricsAggregator`) — projects the connector's raw snapshot/window down to the
   `MetricSpec`/`DimensionSpec` series the feed's `DigestSpec` declares (e.g. clicks, impressions, CTR,
   top queries).
2. **Detect statistically** (`MetricsChangeDetector`) — compares each metric against a rolling EWMA
   baseline persisted on `connector_feed.last_stats` (α ≈ 0.3, ~6-period memory), not just the prior
   period, so a big-but-normal move on a volatile series doesn't read as material. A metric clears the
   gate only if **all three** noise gates pass:
   - **Absolute** — `abs(value − last) >= minAbsolute` (kills small-base nonsense like 2→4).
   - **Relative** — `abs(value − last) / max(abs(last), 1) >= minRelative` (kills large-base jitter like
     4210→4290).
   - **Statistical** — `abs(value − ewma) / sqrt(ewmVar) >= zThreshold` (kills large-but-normal moves on a
     volatile series; skipped as passing below 4 periods of history or while variance is still zero — a
     `lowConfidence` flag on the change record marks this case for the narrator to hedge on).

   Dimension breakdowns (top queries, top pages, …) get the analogous top-N-vs-baseline mover check
   (entered/exited/rank-moved/rose/fell), independent of the metric gates.
3. **Structure** (`DigestPayloadBuilder`) — renders the change-detector's result into the JSON payload
   the narrator reads: which metrics/movers are material, their deltas, direction (`UP_IS_GOOD` vs.
   `DOWN_IS_GOOD` — a falling metric isn't automatically bad news), and the `lowConfidence` flag. This is
   the *only* thing the narrator ever sees — never the raw pulled snapshot.
4. **Narrate** (`metrics-narrator` system workflow) — a single `agent` step turns that JSON into prose
   (title + narrative + optional significance). **The agent has zero tools.** It cannot call
   `write_knowledge_pages` or `submit_knowledge_source` itself — it is a pure text function from digest
   JSON to prose, nothing more. `DigestSubmissionService` reads its output and submits it as a
   `KnowledgeSubmission` into the ordinary inbox — **the platform submits, never the agent.** From there
   it's just another `knowledge_sources` row: the same [pipeline](#the-pipeline) claims it into a lane
   (by the feed's `suggestedDomain`) and the librarian files it onto the page named by the `DigestSpec`'s
   `pagePath`, same as any other source.

**The novelty gate.** A period is material overall only if at least one metric or one dimension mover
cleared its gates — the change-detector's core design decision. Without it, a stable weekly feed would
file ~52 near-identical "clicks were N" pages a year. A non-material period still updates the EWMA
baseline and advances the feed's cursor (so history keeps accumulating and nothing is silently
skipped) — it just records a `SKIPPED` digest row and dispatches no narrator run.

**The quarterly steady-state valve.** The one escape from total silence: `DigestSpec.maxQuietPeriods`
(default 13 — a quarter of weekly periods) counts consecutive non-material periods, and once that
streak is reached the detector forces exactly one emission (`reason: "steady_state"`) and resets the
counter. Otherwise a genuinely flat metric goes silent forever and a reader can't tell "stable" from
"the feed is broken."

**A newly enabled feed files nothing on its own** — it needs either a material period or the
steady-state valve to fire before its first knowledge source ever appears. This is expected, not a
bug: silence is the correct behavior for "nothing changed," and it's also why feed health has its own
surface (below) rather than being inferred from wiki activity.

**Feed health is visible independent of wiki output.** Because "no source filed yet" is the *normal*
state for a freshly enabled or genuinely stable feed, feed health (enabled/cadence, last run, last
success, consecutive failures, last error) is surfaced directly on the connector's own detail page — a
Feeds panel backed by `GET .../integrations/{connectorId}/feeds` (see
[`docs/integrations-adding-a-connector.md`](integrations-adding-a-connector.md#connector-feeds-metrics-digests))
— instead of making an operator infer "is this feed even running?" from whether pages have recently
changed.

---

## Page model

- **`path` is identity.** Lowercase, hyphenated, `.md`-suffixed (`^[a-z0-9_][a-z0-9_/.-]*\.md$`), unique per
  project. `..` segments are rejected; `index.md` and `log.md` are reserved (see below).
- **Frontmatter contract.** Every page is Markdown with a leading `---`/`---` YAML block. `type` is the only
  *required* field — a page with none is rejected (422). `title`, `description`, `resource`, `tags`,
  `timestamp`, `confidence`, `sources` are recommended conventions (defined in `_schema.md`, not enforced by
  the parser); any other key round-trips verbatim so page-type-specific fields survive. Root `_schema.md`
  owns only `schema` and the cross-cutting `decision` type; every other type (`architecture`, `feature`,
  `person`, etc.) is owned by a [domain](#domains)'s own `<slug>/_schema.md` schema page, not the root.
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

Two automation workflows, plus the librarian's `Agent` definition, are provisioned by
`KnowledgeWorkflowProvisioner`, from the YAML templates in `conductor-backend/src/main/resources/knowledge/`.
Provisioning isn't a one-shot on the `false → true` transition: `ProjectSettingsService.updateSettings` calls
it on **every** settings save that leaves `knowledge_enabled` true, and `LibrarianDispatchService` calls it
just-in-time before firing a dispatch if it finds the workflow or the librarian `Agent` row missing. Both
paths are catch-up/self-heal — they cover a project enabled before a given artifact existed, and a seeded
artifact (most often the librarian `Agent`) that was deleted after the fact — coming back on the next
enabled settings save or the next scheduler tick, without an operator having to disable/re-enable. They're
identified purely by reserved workflow name / agent slug (no schema-level "system" flag); every call is
idempotent (upsert-if-missing) so any number of callers racing or repeating never duplicates rows. See
[`docs/workflows.md`](workflows.md#system-managed-workflows) for how they fit the general workflow model.

| Workflow | Trigger | Purpose |
|---|---|---|
| `knowledge-librarian` | `workflow_dispatch`, fired programmatically by `LibrarianDispatchService` — never by a human | A thin `uses: agent` step, `agent: ${{ event.agentSlug }}` resolved per dispatch (see [The pipeline](#the-pipeline)) — the seeded generalist `knowledge-librarian` Agent, or a domain's assigned specialist. Files one batch of claimed sources (one lane's worth) into pages. |
| `knowledge-bootstrap` | `workflow_dispatch` with a required `repo` input (`owner/repo`) | Operator-triggered once, to seed the wiki (`engineering/architecture/*.md`, `engineering/integrations/*.md`, `product/features/*.md`) from an existing codebase by cloning and reading it. Reads the `engineering`/`product` domain schemas for taxonomy/templates rather than the root schema alone. A raw `claude-code` step (no agent involved). |

Both are **system-owned, canonical content**: `KnowledgeWorkflowProvisioner.provision` refreshes a
project's stored workflow YAML in place if it's drifted from the current classpath template (unlike the
wiki schema pages below, which are seed-if-absent only, since those are agent/user-editable).

**The librarian is an `Agent` definition, not a hardcoded prompt.** `KnowledgeWorkflowProvisioner` seeds a
project-scoped `knowledge-librarian` Agent (slug `knowledge-librarian`, provider `claude`, the filing
procedure from `knowledge/librarian-system-prompt.md` as its system prompt, bound to all six
`knowledge:*` tools — see [MCP tools](#mcp-tools) — with `configJson: {"maxToolTurns": 40}`) the same way
it seeds the workflow YAML. The system prompt, model, and tool bindings are all editable afterward under
**Automation → Agents**, same as any other agent — evolving the librarian's behavior no longer requires a
backend change. Its **runtime** (which engine actually executes a run) is decoupled from this definition
entirely and resolves fresh on every dispatch — see
[Runtimes](workflows.md#agent--run-an-ai-agent) in the workflows doc: an explicit `runtime` key in the
agent's `configJson` pins it, otherwise it auto-detects from the project's credentials (a Claude Code
subscription credential is preferred over a `claude` API key when both are configured). Switching which
runtime the librarian runs on is therefore an Agents change, not a workflow edit.

**Operator prerequisites** before either workflow can run — either credential option works for the
librarian; `knowledge-bootstrap` is subscription-only:

- **`knowledge-librarian`**: either the project's **Claude Code (subscription)** credential or a `claude`
  provider API key (**Settings → AI Providers**) — subscription preferred when both are configured. No
  further setup is needed for the `claude-code` runtime's Conductor MCP access — the backend mints a
  short-lived, run-scoped token automatically (see [`docs/workflows.md`](workflows.md)).
- **`knowledge-bootstrap`**: the project's **Claude Code (subscription)** credential (subscription-only —
  it's a raw `claude-code` step, **Settings → AI Providers**), plus a **`GITHUB_TOKEN`** workflow secret
  (**Settings → Workflows → Secrets**) with read access to the target repo, for cloning a private repo.
  Unset works fine for a public repo (the token segment interpolates to empty and `git clone` proceeds
  unauthenticated).

---

## Domains

A **domain** is a top-level wiki area (`engineering/`, `product/`, `marketing/`, `finance/`, `people/` by
default) with its own registry row (`knowledge_domains`, `KnowledgeDomain`/`KnowledgeDomainRepository`)
and its own `<slug>/_schema.md` schema page defining that area's page-type taxonomy, path layout, and
body templates. The root `_schema.md` covers only the frontmatter contract, linking, create-vs-edit, and
the cross-cutting `decision` type — everything else is domain-owned (see [Page model](#page-model)).
`KnowledgeWorkflowProvisioner` seeds all five on enable, the same idempotent guard-then-insert pattern as
the system workflows/schema page.

**Resolution precedence**, run once per submission by `KnowledgeDomainResolver` and stamped onto the
source (never re-resolved later, even if the registry changes afterward):

1. **Explicit caller `domain`** — validated against the project's `ACTIVE` registry; an unknown or
   non-`ACTIVE` slug is rejected (400), not silently dropped or redirected.
2. **First `ACTIVE` domain (slug order) whose `sourceTypePatterns` glob-matches `sourceType`** — `*` is a
   wildcard, everything else is matched literally. Seeded default: `engineering` claims `github.*`; every
   other seeded domain starts with no patterns (PATCH-able per project — see below). Slug order makes
   overlapping globs deterministic.
3. **`null`** — the generalist/unclassified lane. Never strands a source: a domain dismissed or deleted
   after a source was stamped just means dispatch re-resolves the *agent* (not the domain) at claim time,
   falling back to the generalist librarian.

**Lane concurrency** is `(project, domain)` — see [The pipeline](#the-pipeline) for how the scheduler
dispatches and busy-checks each lane independently.

**Registry fields** (`KnowledgeDomainDto`): `slug`, `displayName`, `description`, `pathPrefix`,
`schemaPagePath`, `sourceTypePatterns`, `owningAgentSlug` (nullable — the specialist agent dispatch
resolves to, if assigned; un-FK'd, since agents are deletable and dispatch just falls back), `state`
(`ACTIVE`/`SUGGESTED`/`DISMISSED` — see gap reports below), `suggestionReason`, and live
`pendingCount`/`processingCount`/`processedCount` from the ingestion inbox. `PATCH /knowledge/domains/{slug}`
(ADMIN-only) edits `displayName`/`description`/`sourceTypePatterns`/`state` with standard partial-PATCH
semantics (omit a field to leave it unchanged); `owningAgentSlug` assignment/clearing is a discriminated
pair (`owningAgentSlug` to assign, `clearOwningAgent: true` to clear) rather than a plain nullable field,
since a request body has no wire-level way to distinguish an omitted field from an explicit `null` for
just one field in an otherwise-partial PATCH.

### Specialists on demand

A domain's `owningAgentSlug` can point at a dedicated specialist agent instead of the generalist
librarian. `POST /knowledge/domains/{slug}/specialist` (ADMIN-only, no body) creates (if absent) a
project `Agent` `knowledge-<slug>` — provider `claude`, the same 6 knowledge tools and `maxToolTurns: 40`
as the generalist librarian, a domain-focused system prompt (`specialist-system-prompt.md`, with
`%DOMAIN_SLUG%`/`%DOMAIN_DISPLAY%` placeholders filled in), and a deterministic avatar — then assigns it
as `owningAgentSlug`, one transaction. Idempotent: calling it again when the agent already exists just
re-assigns it. Deliberately **not** added to `DefaultAgentSlugs`: specialists are user-initiated, not
self-healing — deleting one simply clears the assignment and dispatch falls back to the generalist
librarian (see [The pipeline](#the-pipeline)'s agent resolution).

### Gap reports

The librarian (or any agent/caller) can raise a **gap report** for a domain that doesn't exist yet:
`suggest_knowledge_domain` / `POST /knowledge/domains` claim-or-returns on `(projectId, slug)` — the
first call for a slug inserts a `SUGGESTED` row (validated slug shape `^[a-z0-9][a-z0-9-]*$`, since it
becomes a wiki path segment); any later call for the same slug, in any state, returns the existing row
unchanged instead of erroring or resetting it. A `DISMISSED` result is the signal to stop re-suggesting
that slug. Membership-gated, not admin-only — raising a report is cheap and safe; only **approving** one
is privileged: the admin `PATCH .../domains/{slug}` transitioning `state` to `ACTIVE` also seeds a
generic skeleton `<slug>/_schema.md` page (from `_suggested-skeleton.md`) if one isn't already there, so
the domain has somewhere for the librarian to file into immediately — the skeleton explicitly tells
whoever edits it next (librarian or human) to define the domain's actual page-type taxonomy. Dismissing
is the same PATCH with `state: DISMISSED`.

Both the librarian and any specialist are instructed (system prompts) to never invent a new top-level
directory — call `suggest_knowledge_domain` once when sources repeatedly fit no existing domain, and file
into the closest existing home in the meantime rather than leaving material stranded.

---

## Retention

The ingestion inbox (`knowledge_sources`) is append-only by design (see [Concept](#concept)), but that
doesn't mean it grows forever: once a source's payload has served its purpose, keeping the raw content
around indefinitely is pure bloat -- the compounded wiki pages are the durable record, not the inbox.
`KnowledgeRetentionService` runs an hourly sweep with two independent, batch-bounded passes:

| Sweep | Scope | Effect |
|---|---|---|
| **Compact** | `PROCESSED` sources older than `processed-days` (default **30**) | Deletes any offloaded GCS object (`payload_uri`) *first*; only once that succeeds does it null the inline `payload` column and `payload_uri`, then stamp `purged_at`. The row itself -- id, type, ref, metadata, status, timestamps -- is kept, since `knowledge_revision_sources` still references it by id and the wiki's [Log view](#page-model) surfaces source refs by id. Only the (potentially large) payload content is reclaimed. |
| **Delete** | `DEAD` sources older than `dead-days` (default **90**) | Same GCS-first rule, then hard-deletes the row entirely. A `DEAD` source exhausted every retry ([the pipeline](#the-pipeline)'s sweep) without ever being marked `PROCESSED`, so it normally has no downstream references — but a librarian run that outlived the stale window can still link a revision to a source *after* it was dead-lettered, so each row is checked first and a provenance-referenced `DEAD` source is compacted into a tombstone (payload purged, row kept) instead of deleted. |

Each pass processes at most one batch (100 rows) per tick and commits each row's compaction/deletion in
its own transaction, so a large backlog never holds a long-running transaction or blocks the hourly tick.
**A failed GCS delete skips the row entirely** rather than nulling `payload_uri` anyway -- the row is left
untouched and retried on the next hourly tick, so `payload_uri` is never cleared unless the object it
points at is confirmed gone. The alternative (purge the row regardless) would leave that object sitting
in the bucket with nothing left to ever reference or clean it up.

**Configuration** (both accept a day count):

| Property | Env var | Default |
|---|---|---|
| `conductor.knowledge.retention.processed-days` | `KNOWLEDGE_RETENTION_PROCESSED_DAYS` | `30` |
| `conductor.knowledge.retention.dead-days` | `KNOWLEDGE_RETENTION_DEAD_DAYS` | `90` |

**Visibility.** `purgedAt` is exposed on every source read surface -- the `GET /sources` REST endpoint's
`KnowledgeSourceDto`, and the `read_knowledge_sources` MCP tool / agent tool's result -- so a caller can
tell whether a `PROCESSED` source's payload is still available (`purgedAt: null`) or has already been
compacted (`purgedAt` set; `payload`/`payloadOffloaded` will read back empty).

---

## MCP tools

Six tools in `conductor-tools` (`src/mcp/tools/knowledge.ts`), used by the librarian/bootstrap workflows
and available to any Claude Code session with the Conductor MCP server configured:

| Tool | Purpose |
|---|---|
| `submit_knowledge_source` | Push one source into the inbox. Idempotent on `dedupKey`; `DUPLICATE` status is not an error. Optional `domain` requests an explicit [domain](#domains) lane (validated); omitted lets the registry route by `sourceType` pattern. |
| `read_knowledge_sources` | Fetch inbox sources by id, with offloaded payloads resolved inline. |
| `search_knowledge` | Full-text search over pages — path, type, title, description, snippet, rank. Orientation before reading. |
| `read_knowledge_pages` | Fetch full page content by path. `["index.md"]`/`["log.md"]` return the virtual orientation pages. Returned `version` feeds `baseVersion` on the next write. |
| `write_knowledge_pages` | Atomic batch create/update/delete; `writes` may be empty when `sourceIds` is set, to ack a batch that needs no page changes. A stale write returns a structured `{conflict: true, conflicts: [...]}` result instead of throwing, per [MCP tool guidelines](mcp-tool-guidelines.md) — merge and retry once. |
| `list_knowledge_domains` | List the domain registry — slug, displayName, description, pathPrefix, schemaPagePath, sourceTypePatterns, state, owningAgentSlug. Call before `suggest_knowledge_domain`. |
| `suggest_knowledge_domain` | Raise a [gap report](#gap-reports) for a domain not yet in the registry. Claim-or-return on slug — idempotent; a `DISMISSED` result means don't call again for that slug. Verify with `list_knowledge_domains`. |

The same six operations back an `agent`-tool source (`knowledge:read_knowledge_pages`, etc. —
`KnowledgeToolProvider`) so any project agent, not just the librarian, can be bound to them under
**Automation → Agents**. Bare tool names match across both surfaces on purpose — one system prompt works
whether the agent runs the `api` runtime (calling this provider directly) or the `claude-code` runtime
(calling the equivalent `mcp__conductor__*` MCP tool). All six are gated on `knowledge_enabled`.
`KnowledgeWorkflowProvisioner` backfills the two domain tools onto any librarian agent seeded before they
existed (`backfillToolIdsIfMissing`, additive — preserves any custom tool ids an operator added).

---

## REST endpoints

All under `/api/v1/projects/{projectId}/knowledge/`, accepting a user session (project membership via
`ProjectSecurityService`), a project-scoped API key, or a run-scoped workflow MCP token (both the latter
are `ProjectScopedPrincipal` — see [`docs/workflows.md`](workflows.md)):

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/sources` | Submit a source. `202` with `{sourceId, status}`. Optional `domain` requests an explicit lane. |
| `GET` | `/sources` | List by `status` (default `PENDING`), optionally filtered by `domain` (exact match), or multi-get via `ids` (`ids` wins over both filters). |
| `GET` | `/sources/counts` | Per-status inbox counts (`pending`/`processing`/`processed`/`dead`), zero-defaulted — the cheap summary the frontend's health chip and Activity badges poll instead of a full `listSources` per status. |
| `POST` | `/sources/retry` | Reset every `DEAD` source in the project back to `PENDING` (attempts 0, backoff/error cleared) for the scheduler to re-claim. ADMIN-only — an ops recovery action for after fixing the underlying cause (usually the librarian's credential). Returns `{retried}`. |
| `GET` | `/domains` | List the [domain](#domains) registry, slug-ordered, each with live pending/processing/processed counts. Membership-gated, no admin requirement. |
| `POST` | `/domains` | Raise a [gap report](#gap-reports). Claim-or-return on slug — `201` for a new SUGGESTED row, `200` for an existing one (any state). Membership-gated, not admin-only. |
| `PATCH` | `/domains/{slug}` | Update a domain's metadata, owning agent, or state. ADMIN-only. Approving (`state: ACTIVE`) from SUGGESTED also seeds a skeleton schema page if absent. |
| `POST` | `/domains/{slug}/specialist` | Create (or reassign) the `knowledge-<slug>` specialist agent and assign it as owning agent. ADMIN-only, idempotent, no body. |
| `POST` | `/pages/batch-write` | Atomic create/update/delete batch. `200` on success; `409` with a `conflicts` extension on a concurrency race; `422` on malformed frontmatter. |
| `GET` | `/pages?paths=` | Multi-get full page content by comma-separated paths. Unknown/deleted paths silently omitted. |
| `GET` | `/index` | The generated virtual `index.md`. |
| `GET` | `/search?q=` | Full-text search; optional `type`, `pathPrefix`, `limit` (default 20). |
| `GET` | `/revisions?path=` | Revision history for one page, newest first, with actor + source provenance. |

---

## Frontend surfaces

The July 2026 redesign organizes the whole surface around the
[audience-layers model](design-system.md#audience-layers-ia): the reading layer shows content only,
pipeline health compresses to one chip, and configuration lives behind an admin-only Manage page.
UI vocabulary is humanized everywhere: *area* (not domain), inbox statuses *Waiting / Filing /
Filed / Needs attention* (never "dead"), *filing rules* (schema pages), *Assign specialist*.

- **Rail.** Search, **Home**, **Activity**, then the page tree — content pages only
  (`filterContentPages` drops `type: schema` pages; schema-only sections disappear). Pinned footer
  (`KnowledgeRailFooter`): a one-chip health summary — *needs attention* (dead sources or a failed
  last run), *filing n sources…*, *waiting for sources* (enabled but empty wiki), or *up to date* —
  linking into Activity, plus an admin-only **Manage** entry with an amber badge when SUGGESTED
  areas await review. All segments independently best-effort.
- **Home** (`knowledge/`). Header (page count, last-updated from the revision log), **Recently
  updated** (newest log entries deduped per page, cross-referenced against the index for
  title/type; each row links to the page and names its first source ref), and **Browse by area**
  (ACTIVE areas joined with the index; areas with no pages collapse into one muted card). Both
  sections are auxiliary and best-effort. Empty-wiki and not-enabled states are distinct — since
  `GET /settings` is admin-only, enabled-ness is inferred role-agnostically from the seeded
  librarian agent's presence (`listAgents` is membership-gated): not enabled shows the Enable
  action + Claude connection hint (`listProviderCredentialStatuses` — guidance, not a gate);
  enabled-but-empty shows the **first-run onboarding** — "The librarian is
  on duty", the three source tiles (work items / merged PRs / codebase), and an admin-only
  **Bootstrap from a repo** dialog that dispatches `knowledge-bootstrap` directly.
- **Activity** (`knowledge/activity`, tab param). One destination for "what is the librarian
  doing": **Page changes** (rendered virtual `log.md`), **Inbox** (the status-filtered source
  browse, moved from `knowledge/sources` which now redirects here; red count badge when sources
  need attention), and **Runs** (librarian run history). When sources are stuck, the Inbox shows an
  attention banner pairing the diagnosis with its fixes — **Open AI Providers** and an admin-only
  **Retry n sources** (`POST /sources/retry`).
- **Manage** (`knowledge/manage`, admin-only). An **Ingest cadence** setting (15 min / hourly /
  daily presets, backed by `knowledgeIngestIntervalMinutes`) up top, then the area registry:
  SUGGESTED areas as approval cards (Approve seeds the skeleton schema, Dismiss declines), then
  ACTIVE areas with routing patterns, owning agent ("Librarian" fallback), waiting counts,
  **Assign specialist** where unowned, and a **Filing rules** link to each area's schema page.
- **Default agent chip.** The Agents list, an agent's detail header, and `AgentResponse.isDefault`
  together surface which agents (e.g. the librarian) are seeded by Conductor rather than user-created.
  Deleting one is allowed — the chip's tooltip says it will be recreated. The librarian's Overview tab
  also cross-links to its workflow's Runs tab.

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
