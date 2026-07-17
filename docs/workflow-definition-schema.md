# Workflow Definition Schema (COND-18 Foundation)

> **Status:** Foundation contract for COND-18 ("Conductor Workflows — Foundation"). This is the
> **single, versioned, machine-validated contract** every Workflow consumer reads and writes — the
> `WorkflowEngine`, the `WorkflowDefinitionValidator`, the no-code Builder, the MCP/CLI surface, and
> authoring skills. Author it first; everything else binds to it.

## Where it lives
- **Schema:** [`conductor-backend/src/main/resources/schema/workflow-definition-v1.schema.json`](../conductor-backend/src/main/resources/schema/workflow-definition-v1.schema.json)
  (JSON Schema draft 2020-12).
- **Worked example:** [`.../schema/examples/engineering.workflow.json`](../conductor-backend/src/main/resources/schema/examples/engineering.workflow.json)
  — today's Engineering loop expressed as a definition.

A Workflow definition is stored as the `definition JSONB` column on the extended `workflow_definitions`
table (alongside `version`, `state`, `area`, `schemaVersion`). It is **validated in Java**; the validator
is the **sole write path** for both the Builder and any authoring skill/MCP tool, so all producers inherit
the same guardrails.

## Two layers of validation
The contract is split deliberately, because JSON Schema can express structure but not whole-document
semantics:

1. **Structural — enforced by the JSON Schema** (this file): field types, enums, required keys, and the
   hard caps that are per-array (≤10 statuses, ≤3 steps/transition, review outcomes ≥2, `schemaVersion`
   routing, no unknown keys). These reject at parse time.
2. **Semantic — enforced by `WorkflowDefinitionValidator` in Java** (listed under `x-semantic-rules` in the
   schema): exactly one initial status, ≥1 terminal, full reachability (no dead-ends/unreachable statuses),
   `from`/`to` reference real status ids, ≤5 transitions per source status, ≤3 review-gated transitions,
   and `skill` steps reference a registered skill id (`AC-P0-2.5`). These are the **warn-while-editing /
   block-at-publish** rules (P0-2).

> Keep the two in sync: when a cap or rule changes, update both the schema (if structural) and the Java
> validator (if semantic), and add a case to the schema's validation test.

## Definition fields (summary)
| Field | Required | Notes |
|---|---|---|
| `schemaVersion` | ✓ | `1`. Routes validation. |
| `id` | ✓ | Stable UPPER_SNAKE slug, referenced everywhere (never a UUID). |
| `area` | ✓ | Nav-grouping slug. Single-Workflow Areas render flat. |
| `version` | ✓ | Monotonic; in-flight Work Items pin to their version. |
| `state` | ✓ | `DRAFT` \| `PUBLISHED`. Only `PUBLISHED` is bindable. |
| `noun` | ✓ | Display noun (e.g. `Issue`, `Post`). |
| `default_view` | ✓ | `list` \| `board` \| `calendar`. |
| `types` | ✓ | Allowed Work Item types (strings, not a DB enum). |
| `asset_types` | – | Allowed produced-output Asset types. |
| `metric` | – | `null` to opt out, else `{name, unit?, direction}`. |
| `statuses` | ✓ | `{id, label?, category, initial?, terminal?}`; ≤10. `label` is the human display name (falls back to a humanized id). |
| `transitions` | ✓ | `{from, to, label, requiresReview?, reviewOutcomes?, reviewerRole?, trigger?, steps?}`. |
| `triggers` | – | `{type: manual\|schedule\|status_changed\|webhook, …}`. |

**Transition `trigger` (system-advanced edges):** a transition may declare `"trigger": "pr_merged"` to be
fired automatically by an external event instead of a human action. When a linked GitHub pull request
merges, the engine advances the Work Item along the `pr_merged` edge **out of its current status** (for
ENGINEERING that is `CODE_REVIEW → DONE`), bypassing the Review gate (the merge is the authority). If the
Work Item is not at a status that has a `pr_merged` edge, the merge is recorded as a `github_pr` Asset and
the status is left unchanged. `pr_merged` is the only system trigger in v1; it is extensible.

**Step kinds (v1):** `skill`, `http`, `notify`, `set_field`, `create_sub_items` (each `BLOCKING` or
`ASYNC`, with a `type_version`). A `skill` step names a bindable skill (e.g. `conductor:implement`); for a
skill step `BLOCKING` is an **advisory convention the local driver honors** — the engine does not run the
skill (see `engineering-migration.md`).

**Post-v1 integration seam (reserved, rejected by the v1 validator):** the `connector_fetch` /
`connector_action` step kinds and the `connector_event` trigger bind the shipped connector framework to the
engine. They are documented in the PRD's *Future Directions #3* and the architecture's *two bounded contexts
(DDD)* section, and are intentionally **not** part of the v1 contract.

## The ENGINEERING example — faithful to the legacy behavior
`examples/engineering.workflow.json` reproduces the exact pre-engine hardcoded transition set (the former
`IssueService.VALID_TRANSITIONS`, since removed — enforcement now lives in `WorkItemWorkflowService`),
verified by an edge-set diff: the linear `DRAFT → … → DONE` spine **plus** `CLOSED` reachable from every
non-terminal status **and** the `IN_REVIEW → DRAFT` back-edge. It binds `conductor:implement` to
`READY_FOR_DEVELOPMENT → IN_PROGRESS` (advisory). This is the seed for **Phase 1 (Engineering-no-regression)**,
whose bar is `AC-P0-1.1` — existing issues must transition **identically** after the engine swap.

### The review gate — now enforced and role-scoped
The `CODE_REVIEW → DONE` transition is a **server-enforced, role-scoped Review gate**:

```json
{ "from": "CODE_REVIEW", "to": "DONE", "label": "Merge",
  "requiresReview": true, "reviewOutcomes": ["approve", "request_changes"],
  "reviewerRole": "REVIEWER", "trigger": "pr_merged" }
```

`WorkItemWorkflowService.validateTransition` blocks a `requiresReview` transition until an **APPROVED**
review exists from a project member holding the transition's `reviewerRole` — or an `ADMIN`, who outranks
any review role. When a transition declares no `reviewerRole`, any APPROVED review satisfies the gate. The
doer projection (`available-transitions`) hides a review-gated edge until the gate is satisfied. Note the
gate is bypassed for the system `pr_merged` trigger (the merge is the authority).

## Generalization runtime model (Waves 1–6)
The whole stack now runs on the Workflow definition rather than hardcoded enums:

- **Status/type are Workflow-defined strings.** The `work_items.current_status` and `work_items.item_type`
  columns (renamed from `issues.*` in `V75`) are authoritative. A new Work Item's initial status is the
  chart's `initial` status; `type` is validated against the chart's `types`. The v2 REST surface
  (`WorkItemResponse`, patch requests, list filters — see `openapi-v2.yaml`) carries plain strings, so any
  custom Workflow's statuses/types flow end-to-end.
- **Version pinning.** Each publish writes an immutable snapshot to `workflow_definition_versions` and
  advances the version. A Work Item pins `workflow_version` at creation and always resolves that snapshot,
  so re-publishing never changes the rules under an in-flight Work Item. The resolver is DB-snapshot-first
  with a built-in classpath fallback.
- **Notifications.** Status changes fire a single, Workflow-agnostic `WORK_ITEM_STATUS_CHANGED` event enriched
  with `noun`, `toStatus`, `toStatusLabel`, and `toCategory`; the Discord provider formats it generically
  (color by status category). The legacy per-status events were removed.
- **Read model for the UI.** `GET /projects/{projectId}/workflows/by-slug/{slug}?version=` returns a lean
  `WorkflowView` (noun, statuses with labels+categories, transitions, types, asset types, metric) resolved
  for built-in and project-authored workflows alike. `GET .../workflows/{workflowId}/versions` lists the
  published history. The Active/Done split in the UI is derived from status `category`
  (`open`/`in_progress` = active, `terminal` = done).
- **Authoring.** Lifecycle workflows are created/edited as DRAFTs and promoted via
  `POST .../workflows/{id}/publish` (ADMIN/CREATOR only), which runs the validator before snapshotting.

The seed `engineering.workflow.json` reproduces today's exact transition set; existing issues keep working
because their `current_status`/`item_type` mirror the former enum values and ENGINEERING's `initial` status
is `DRAFT`.

## Workflow-driven Work Item views & navigation (COND-22)
The definition's `default_view`, `noun`, `area`, `statuses`, and `types` drive the UI directly — no
hardcoded Issues page remains.

- **Per-project seeding.** ENGINEERING is now a real `workflow_definitions` row per project (PUBLISHED,
  `version=1`), not just a classpath fallback, so it appears in the workflow list API. Existing projects
  are seeded by Flyway migration `V74__seed_engineering_workflow` (reads the canonical classpath JSON);
  new projects are seeded by `WorkflowSeeder` from `ProjectService.createWorkspace`. Both are idempotent.
- **Generic Work Item pages.** The workflow-scoped routes `/app/projects/{projectId}/{area}/{noun}` (list)
  and `.../{area}/{noun}/{displayId}` (detail) resolve the `WorkflowView` and render Work Items in the
  `default_view` — title from `pluralize(noun)`, status/type filters from the view. Work Items are fetched
  workflow-scoped via the v2 work-items API (`GET .../work-items?workflow={slug}`).
- **Dynamic sidebar.** The sidebar lists sidebar-enabled, published lifecycle workflows, grouped by
  humanized `area`, labelled `pluralize(noun)`, linking to `/work/{slug}`. It falls back to a static
  Issues entry when none resolve.
- **`sidebar_enabled`.** A boolean column on `workflow_definitions` — **not** part of the versioned
  `definition`, so it toggles live via `PATCH /projects/{projectId}/workflows/{workflowId}/sidebar`
  without republishing. Defaults to `false`; ENGINEERING is seeded `true`. `GET .../workflows?sidebar=true`
  filters by it (composes with `lifecycle` and `state`).
- **Explicit `kind`.** `WorkflowDefinitionDto` carries `kind` = `LIFECYCLE | AUTOMATION`, derived
  server-side (`WorkflowDefinition.isLifecycle()` — a non-empty statechart `definition`). Clients (sidebar,
  workflows list, lifecycle editor) classify on `kind`, never on the shape of `definition`. The DTO also
  returns `definition: null` for automations rather than `{}`, so a YAML automation is never mistaken for a
  statechart workflow.

## Validating a definition
The schema and example are checked with ajv (draft 2020-12). From `conductor-tools/`:

```js
const Ajv2020 = require('ajv/dist/2020').default;
const ajv = new Ajv2020({ allErrors: true, strict: false });
const validate = ajv.compile(require('<schema path>'));
validate(require('<definition>')); // → boolean; validate.errors on failure
```

The Java `WorkflowDefinitionValidator` (P0-1/P0-2) is the runtime authority and additionally enforces the
`x-semantic-rules`. This document and the schema are the contract both the validator and every producer
implement against.
