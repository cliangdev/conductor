# @conductor/cli

CLI for managing Conductor issues and syncing documents locally.

## Installation

```bash
npm install -g @conductor/cli
```

Or build from source:

```bash
cd conductor-tools
npm install
npm run build
npm link          # makes `conductor` available globally
```

## Setup

```bash
conductor login       # authenticate via browser (Google OAuth, prod)
conductor login --local  # authenticate via email/password (local dev)
conductor init        # connect to a project and configure local sync path
conductor start       # start the local file sync daemon
```

After `init`, issues sync to `~/.conductor/{projectId}/issues/` and Claude Code can read them directly via the MCP server.

## Local Dev vs Prod

### Local dev (`make dev`)

The local stack runs at `http://localhost:8080` (backend) and `http://localhost:3000` (frontend). It uses email/password auth — no Firebase/Google required.

```bash
# First time: login with email/password
CONDUCTOR_API_URL=http://localhost:8080 conductor login --local
# Prompts for email and password (default: dev@example.com / conductor)

# Verify
conductor config show
conductor doctor
```

If you've already logged in to prod and want to switch to local:

```bash
conductor config set-url http://localhost:8080
```

### Prod (Cloud Run)

```bash
# First time: opens browser for Google sign-in
CONDUCTOR_API_URL=<prod-url> conductor login

# Already logged in locally — just swap the URL
conductor config set-url <prod-url>

# Verify
conductor config show
conductor doctor
```

## Commands

| Command | Description |
|---------|-------------|
| `conductor login` | Authenticate via browser (Google OAuth, prod) |
| `conductor login --local` | Authenticate via email/password (local dev only) |
| `conductor logout` | Clear stored credentials |
| `conductor init` | Connect to a project and set up Claude Code MCP integration |
| `conductor start` | Start the background sync daemon (file watcher, 500ms debounce) |
| `conductor stop` | Stop the sync daemon |
| `conductor status` | Show sync daemon status and current project config |
| `conductor config show` | Print current config (apiKey redacted) |
| `conductor config set-url <url>` | Hot-swap API URL without re-auth |
| `conductor issue list` | List issues in the project |
| `conductor issue create` | Create a new issue |
| `conductor doc pull <issueId>` | Download a document draft to the local sync path |
| `conductor doc push <issueId>` | Upload a local document draft to the cloud |
| `conductor doctor` | Check config and API connectivity |
| `conductor mcp` | Start the MCP server (stdio transport) — used internally by Claude Code |

## MCP Integration

`conductor init` automatically adds the MCP server entry to your Claude Code config (`~/.claude/mcp.json`):

```json
{
  "mcpServers": {
    "conductor": {
      "command": "conductor",
      "args": ["mcp"]
    }
  }
}
```

Once configured, Claude Code can use these tools:
- `create_issue` — create a new issue
- `list_issues` — fetch all issues
- `get_issue` — fetch a single issue with its document
- `update_issue` — update issue title, status, or type
- `create_document` — write a document draft for an issue
- `update_document` — update an existing document draft
- `get_document` — fetch the current document content
- `list_documents` — list all documents for an issue

## Local Files

```
~/.conductor/
├── config.json               # auth + project config
└── {projectId}/
    └── issues/
        └── {issueId}.md      # local document drafts
```

The sync daemon watches the `issues/` directory and pushes changes to the API with a 500ms debounce. Offline changes are queued at `~/.conductor/sync-queue.json` and flushed when connectivity is restored.

## Configuration

`~/.conductor/config.json` schema:

```json
{
  "apiUrl": "https://api.example.com",
  "apiKey": "...",
  "email": "user@example.com",
  "projectId": "abc123",
  "localPath": "/path/to/project"
}
```

Override the API URL with the `CONDUCTOR_API_URL` environment variable.

## E2E Testing

See [TESTING.md](./TESTING.md) for the full step-by-step test procedure for both CLI and MCP against prod.
