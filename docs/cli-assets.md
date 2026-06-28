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
