# Conductor MCP Tool Guidelines

Authoritative reference for **creating or updating any MCP tool** in `conductor-tools`. Read this before
touching `conductor-tools/src/mcp/`. Pair it with `docs/api-guidelines.md` for backend API conventions.

---

## 1. Context budget

MCP tool **descriptions** are loaded into the LLM context window at session start, every session — unlike
skill bodies (`.md` files), which are lazy-loaded only when invoked. A verbose description taxes every
session even if the tool is never called.

Keep descriptions lean: what the tool returns, when to call it, and what to call next. No inline JSON
examples — put those in the skill file. One paragraph of description is a few hundred tokens every
session. Ten tools × long descriptions is real cost that compounds across every user interaction.

---

## 2. Minimal tool surface

One tool = one user intent. Never mirror the backend REST API 1:1.

Prefer a **discover-then-act** pattern: a discovery tool (`list_integration_tools`) reveals available
capabilities at runtime from the database, then authoring tools act on them. This avoids the "abstraction
tax" — wrapping a complex API 1:1 just adds layers without preserving the underlying expressiveness.

Avoid exposing raw CRUD. The agent shouldn't need to know about internal IDs, pagination, or filter
params unless they directly serve a user intent.

**Two tools for the same intent is sometimes correct** — when the environments they run in genuinely
differ. `write_document` (upsert by filename, full content) works headlessly (e.g. inside a
`claude-code` workflow step container) and is preferred; `scaffold_document` requires the local
daemon (it hands back a local path for the `Write` tool to fill in) and only makes sense in an
interactive Claude Code session. Each tool's description says which one to prefer and why, so the
agent doesn't have to guess from context which environment it's in.

---

## 3. No false promises

If a parameter is documented, it must do something. A silently-ignored param is worse than no param —
the agent wastes context writing something that has no effect.

If a field is truly advisory (for documentation/discoverability only), say so explicitly in the
description: "This field is informational — it does not change what data is fetched."

Make parameters functional before shipping, or don't ship them.

---

## 4. Action–verify pattern

Every mutating tool must have a read-back companion.

The mutating tool's description must name the companion: "Always call `get_workflow` after to verify the
stored result." This closes the observability loop and prevents the agent from proceeding on stale
assumptions.

**Canonical pair:** `create_workflow` → `get_workflow`.

---

## 5. Dispatch–status pattern

Async actions (fire-and-forget triggers) must have a status-check companion.

The dispatch tool's description must name the companion: "Call `get_workflow_run` after to verify the run
started." Don't instruct the agent to poll in a loop — one check is enough for test-run verification.

**Canonical pair:** `dispatch_workflow` → `get_workflow_run`.

---

## 6. Credential safety

Credentials never pass through MCP tools. Use integration indirection: the workflow YAML references
`uses: integration / connector: <id>`, and the backend resolves the active connection at runtime.

If a tool needs auth context, it should accept an ID (connection ID, issue ID) and let the backend
resolve credentials internally. Never accept API keys, tokens, or secrets as tool parameters.

---

## 7. Dynamic discovery over static schemas

Surface capability metadata at runtime (queried from the DB), not as hardcoded descriptions or example
payloads.

Static schemas (hardcoded in the tool description) go stale as connectors are added or changed.
DB-backed metadata (`list_integration_tools` reads `tool_metadata` JSONB from the connection row) stays
current without a tool update.

The "abstraction tax" principle: each hardcoded layer between the agent and the real data loses fidelity.
Let the agent discover the real shape of available tools rather than summarizing it upfront.

---

## 8. Checklist for adding / updating an MCP tool

- [ ] Description is lean — no inline JSON examples; states what it returns and what to call next
- [ ] Tool does exactly one thing (one user intent)
- [ ] Every documented parameter is actually used by the implementation
- [ ] Mutating tools: named a read-back companion in the description
- [ ] Async dispatch tools: named a status-check companion in the description
- [ ] Credentials are not accepted as parameters — use integration indirection
- [ ] Discovery data comes from the DB at runtime, not hardcoded in the description
- [ ] If the internal API changed: all in-repo callers updated + CLI version bumped

---

See also `docs/api-guidelines.md` for backend API conventions. The conductor MCP server is defined in
`conductor-tools/src/mcp/`.
