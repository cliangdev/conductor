# CLI Assets — Lifecycle Guide

Claude Code assets (commands, skills, agents) that ship with the Conductor CLI and are installed into users' `.claude/` directories via `conductor init`.

## Asset types and naming

All conductor assets use the `conductor-` prefix so they're easy to identify in a user's Claude Code setup.

| Type | Location in repo | Installed to `.claude/` | Invoked as |
|------|-----------------|------------------------|------------|
| Command | `assets/claude/commands/conductor/<name>.md` | `.claude/commands/conductor/<name>.md` | `/conductor:<name>` |
| Skill | `assets/claude/skills/conductor-<name>/SKILL.md` | `.claude/skills/conductor-<name>/SKILL.md` | `conductor-<name>` via Skill tool |
| Agent | `assets/claude/agents/conductor-<name>.md` | `.claude/agents/conductor-<name>.md` | `subagent_type: "conductor-<name>"` |

Skill frontmatter must set `name: conductor-<name>` to match the directory name.

## Writing domain-agnostic guidance

Conductor is a generic agentic-orchestration platform — engineering, marketing, knowledge, and
whatever other domains a project defines (see the pillar list in the root `CLAUDE.md`) — not a
GitHub- or code-review-specific tool. Skill/command prose that only ever illustrates a generic
platform capability with one connector or one domain's example makes that capability read as if
it's scoped to that domain, and a future agent working in an unrelated domain may skip guidance
that looks like it doesn't apply to them.

This bit us concretely: `/conductor:workflow` picked up three additions in the same pass
(credentials binding, job-level `if:` scope, a job-gating idiom) that all used the same GitHub
PR-review example. Two of the three are actually trigger/connector-agnostic engine features, not
GitHub-specific ones — the repeated example made them read otherwise.

**When writing or editing skill/command/agent content:**
- State a generic platform capability's rule on its own, independent of any one domain, before
  reaching for a worked example.
- If you use a worked example, label it explicitly as one instance of the rule ("one instance of
  this idiom is...") rather than letting the example stand in for the rule.
- When a pass touches multiple pieces of guidance, don't reuse the same single domain/connector as
  the example for all of them — vary it, or call out a second domain explicitly, so the generality
  reads from the text itself.

This applies the same way to MCP tool descriptions — see
[`docs/mcp-tool-guidelines.md`](mcp-tool-guidelines.md#8-domain-agnostic-examples).

## Adding a new asset

1. Create the file under `conductor-tools/assets/claude/` following the naming convention above.
2. Add its path to `PLUGIN_FILES` in `conductor-tools/src/lib/plugin-assets.ts`.
3. A Vitest completeness test (`src/__tests__/plugin-assets.test.ts`) will fail CI if you forget step 2.
4. Add an entry to `conductor-tools/assets/cli-manifest.json` under `claudeIntegration.slashCommands`, `.skills`, or `.agents` so the **Settings → CLI** page in the web app stays current.

## Renaming or removing an asset

1. Rename/delete the file.
2. Update `PLUGIN_FILES` to point at the new path (or remove the entry).
3. Add the **old** path to `LEGACY_PATHS` in `plugin-assets.ts` so `conductor init` cleans it up from existing installs.

## Adding or updating a CLI command

CLI commands live in `conductor-tools/src/commands/`. After adding or changing a command, update `conductor-tools/assets/cli-manifest.json` — add or edit the entry in `commands[]` with the command's `name`, `syntax`, `description`, `category`, and any `options`. This file drives the **Settings → CLI** reference page in the web app.

## Adding or updating an MCP tool

MCP tools live in `conductor-tools/src/mcp/tools/`. The MCP server auto-discovers tools at startup — no registration step required. Follow the guidelines in [`docs/mcp-tool-guidelines.md`](mcp-tool-guidelines.md).

Also update `conductor-tools/assets/cli-manifest.json` — add an entry to `mcpTools[]` with the tool's `name`, `description`, `category`, and any `requiredParams`. This file is published with the npm package and drives the **Settings → CLI** reference page in the web app.

## Releasing

Version is single-sourced in `conductor-tools/package.json`. The CI pipeline (`release-cli.yml`) auto-publishes to npm when `package.json` changes on `main`.

```bash
cd conductor-tools
npm version patch    # or minor / major
--no-git-tag-version  # don't tag locally; CI tags on release
```

Open a PR and merge. CI publishes, tags `cli-vX.Y.Z`, and creates a GitHub Release.

## User update flow

```bash
npm update -g @cliangdev/conductor   # pull latest CLI binary
conductor init                        # re-install / update Claude assets
```

`conductor init` is idempotent — it overwrites changed assets and removes any `LEGACY_PATHS` without touching unrelated files.
