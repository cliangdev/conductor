# Conductor

**Agentic software development — from idea to launch, with AI and humans in the loop.**

[![npm](https://img.shields.io/npm/v/@cliangdev/conductor)](https://www.npmjs.com/package/@cliangdev/conductor)

## What is Conductor?

Conductor is an agentic software development platform that manages the entire software development lifecycle using AI agents and human collaboration. You bring the intent; agents handle the execution.

Today, Conductor covers product requirements (PRD), team review, and AI-driven implementation. The roadmap extends across the full SDLC — testing, deployment, monitoring, and incident response — every phase driven by agents, with humans in the loop at the moments that matter.

The platform is built around Claude Code. You write PRDs with AI, your team reviews and approves them, then Claude implements them — opening PRs, running tests, and tracking progress — all coordinated through Conductor.

## How it works

```
1. Write a PRD     →   /conductor:prd in Claude Code
2. Team reviews    →   Conductor web app — comment, approve, request changes
3. Implement       →   /conductor:implement in Claude Code
4. PR opens        →   Claude commits, pushes, and creates the pull request
5. Merge           →   Issue closes automatically
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

## MCP Tools

Once `conductor init` runs, Claude Code gets access to these tools via the Conductor MCP server:

| Tool | Description |
|------|-------------|
| `create_issue` | Create a new PRD or task |
| `list_issues` | List issues with optional filters |
| `get_issue` | Fetch a single issue with its document |
| `update_issue` | Update title or description |
| `set_issue_status` | Advance issue through the workflow |
| `scaffold_document` | Create a new document attached to an issue |
| `delete_document` | Remove a document |
| `list_issue_comments` | Fetch reviewer comments on an issue |

## Links

- **GitHub**: [github.com/cliangdev/conductor](https://github.com/cliangdev/conductor)
- **Web app**: [conductor-frontend-199707291514.us-central1.run.app](https://conductor-frontend-199707291514.us-central1.run.app)

---

## For Contributors & Local Development

### Local dev setup

The local stack runs at `http://localhost:8080` (backend) and `http://localhost:3000` (frontend) with email/password auth — no Firebase required.

```bash
# First-time login against local stack
CONDUCTOR_API_URL=http://localhost:8080 conductor login --local
# Default credentials: dev@example.com / conductor

# Verify
conductor config show
conductor doctor
```

Switching between local and prod:

```bash
conductor config set-url http://localhost:8080   # local
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

Override the API URL: `CONDUCTOR_API_URL=http://localhost:8080 conductor login --local`

### Testing

See [TESTING.md](./TESTING.md) for the end-to-end test procedure for CLI and MCP against prod.
