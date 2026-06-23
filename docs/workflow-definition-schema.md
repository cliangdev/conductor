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
| `statuses` | ✓ | `{id, category, initial?, terminal?}`; ≤10. |
| `transitions` | ✓ | `{from, to, label, requiresReview?, reviewOutcomes?, reviewerRole?, steps?}`. |
| `triggers` | – | `{type: manual\|schedule\|status_changed\|webhook, …}`. |

**Step kinds (v1):** `skill`, `http`, `notify`, `set_field`, `create_sub_items` (each `BLOCKING` or
`ASYNC`, with a `type_version`). A `skill` step names a bindable skill (e.g. `conductor:implement`); for a
skill step `BLOCKING` is an **advisory convention the local driver honors** — the engine does not run the
skill (see `engineering-migration.md`).

**Post-v1 integration seam (reserved, rejected by the v1 validator):** the `connector_fetch` /
`connector_action` step kinds and the `connector_event` trigger bind the shipped connector framework to the
engine. They are documented in the PRD's *Future Directions #3* and the architecture's *two bounded contexts
(DDD)* section, and are intentionally **not** part of the v1 contract.

## The ENGINEERING example — faithful to today
`examples/engineering.workflow.json` reproduces **today's exact** `IssueService.VALID_TRANSITIONS`
(verified by an edge-set diff): the linear `DRAFT → … → DONE` spine **plus** `CLOSED` reachable from every
non-terminal status **and** the `IN_REVIEW → DRAFT` back-edge. It binds `conductor:implement` to
`READY_FOR_DEVELOPMENT → IN_PROGRESS` (advisory). This is the seed for **Phase 1 (Engineering-no-regression)**,
whose bar is `AC-P0-1.1` — existing issues must transition **identically** after the engine swap.

### Deliberate Phase-2 diff — the review gate
The seed ships the `CODE_REVIEW → DONE` transition **ungated**, because today that approval is *not*
enforced (`IssueService` has no `ReviewRepository` dependency; reviews are advisory). Turning it into a
**server-enforced Review gate** is the *one true behavior change* in the foundation and is shipped in
**Phase 2 (P0-1.3 / P0-6)** as an explicit, separately-reviewed change — set on that transition:

```json
{ "from": "CODE_REVIEW", "to": "DONE", "label": "Merge",
  "requiresReview": true, "reviewOutcomes": ["approve", "request_changes"], "reviewerRole": "REVIEWER" }
```

Keeping the gate out of the no-regression seed is what lets Phase 1 honestly claim "no behavior change,"
and makes the gate an auditable, opt-in step rather than a silent change to every project's Engineering flow.

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
