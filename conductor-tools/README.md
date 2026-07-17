# Conductor

**Agentic software development — from idea to launch, with AI and humans in the loop.**

[![npm](https://img.shields.io/npm/v/@cliangdev/conductor)](https://www.npmjs.com/package/@cliangdev/conductor)

## What is Conductor?

Conductor is the coordination layer for an agentic organization: AI agents do the work — author specs, write code, run automations, maintain the knowledge base — and humans review, approve, and steer. The platform combines work items with review gates, lifecycle + automation workflows, named agents, a Knowledge Center, and third-party integrations.

This package is the local toolkit: a CLI for auth/sync and an MCP server that gives Claude Code (or any MCP client) full access to the platform.

## How it works

```
1. Write a PRD     →   /conductor:prd in Claude Code
2. Team reviews    →   Conductor web app — comment, approve, request changes
3. Implement       →   /conductor:implement in Claude Code
4. PR opens        →   Claude commits, pushes, and creates the pull request
5. Fix & iterate   →   /conductor:fix — address review feedback on the open PR
6. Merge           →   Work item closes automatically
```

Agents do the execution. Humans set the intent and sign off.

## Quick Start

```bash
# Install
npm install -g @cliangdev/conductor

# Authenticate (opens browser for Google sign-in)
conductor login

# Connect to a project and configure Claude Code integration
conductor init

# Start the background sync daemon
conductor start
```

Then open Claude Code in your project and run `/conductor:prd` to create your first PRD.

## Claude Code Commands

| Command | What it does |
|---------|-------------|
| `/conductor:prd` | Guides you through writing a PRD with AI — discovery, research, structured output |
| `/conductor:implement` | Takes an approved PRD and implements it — task breakdown, parallel subagents, PR creation |
| `/conductor:fix` | Fixes bugs and review feedback on an open PR — structured intake, investigation, build validation, and push |
| `/conductor:workflow` | Designs and creates a Conductor workflow — YAML automation or lifecycle statechart — via guided discovery |

These commands are installed automatically when you run `conductor init` (project-level) or during global install (user-level, to `~/.claude/`).

## CLI Commands

| Command | Description |
|---------|-------------|
| `conductor login` | Authenticate via browser (Google OAuth) |
| `conductor logout` | Clear stored credentials |
| `conductor init` | Connect to a project and set up Claude Code MCP integration |
| `conductor start` | Start the background sync daemon |
| `conductor stop` | Stop the sync daemon |
| `conductor status` | Show daemon status and sync queue |
| `conductor doctor` | Check config, API connectivity, and Claude Code integration |
| `conductor config show` | Print current config (API key redacted) |
| `conductor config set-url <url>` | Hot-swap API URL without re-auth |
| `conductor dashboard` | Live terminal view of daemon, sync queue, and active workflow runs |
| `conductor lint [issueId]` | Validate local work-item files |
| `conductor mcp` | Run the MCP server (stdio) — wired into Claude Code by `conductor init` |

## MCP Tools

Once `conductor init` runs, Claude Code gets access to 32 tools via the Conductor MCP server, grouped by area:

| Area | Tools |
|------|-------|
| Work items | `create_work_item`, `update_work_item`, `get_work_item`, `list_work_items`, `set_work_item_status`, `get_available_transitions`, `transition_work_item`, `list_work_item_comments`, `record_asset` |
| Documents | `write_document` (headless upsert), `scaffold_document` (local file, needs daemon), `delete_document` |
| Workflows | `list_workflows`, `create_workflow`, `get_workflow`, `update_workflow`, `publish_workflow`, `dispatch_workflow`, `get_workflow_run`, `list_workflow_runs`, `list_workflow_secrets`, `report_step_run` |
| Integrations & discovery | `list_integration_tools`, `list_connector_catalog`, `list_agents`, `list_skills`, `register_skill` |
| Knowledge | `submit_knowledge_source`, `read_knowledge_sources`, `search_knowledge`, `read_knowledge_pages`, `write_knowledge_pages` |

Tool descriptions embed the usage contract (discover-then-create, action–verify) — see [docs/mcp-tool-guidelines.md](../docs/mcp-tool-guidelines.md).

## Links

- **GitHub**: [github.com/cliangdev/conductor](https://github.com/cliangdev/conductor)
- **Web app**: [conductor-frontend-199707291514.us-central1.run.app](https://conductor-frontend-199707291514.us-central1.run.app)

---

## For Contributors & Local Development

### Local dev setup

The local stack runs at `http://localhost:8090` (backend) and `http://localhost:3000` (frontend) with email/password auth — no Firebase required.

```bash
# First-time login against local stack
CONDUCTOR_API_URL=http://localhost:8090 conductor login --local
# Default credentials: dev@example.com / conductor

# Verify
conductor config show
conductor doctor
```

Switching between local and prod:

```bash
conductor config set-url http://localhost:8090   # local
conductor config set-url <prod-url>              # prod
```

### Build from source

```bash
cd conductor-tools
npm install
npm run build
npm link          # makes `conductor` available globally
```

### Configuration

`~/.conductor/config.json`:

```json
{
  "apiUrl": "https://...",
  "apiKey": "...",
  "email": "user@example.com",
  "projectId": "...",
  "localPath": "/path/to/project"
}
```

Override the API URL: `CONDUCTOR_API_URL=http://localhost:8090 conductor login --local`

### Testing

See [TESTING.md](./TESTING.md) for the end-to-end test procedure for CLI and MCP against prod.
