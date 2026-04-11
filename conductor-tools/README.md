# @conductor/cli

Command-line interface for Conductor — manage issues, sync documents, and wire up Claude Code integration.

## Installation

```bash
npm install -g @conductor/cli
```

Or build from source:

```bash
cd conductor-tools
npm install
npm run build
npm link
```

## Setup

```bash
conductor login       # authenticate via browser (Google OAuth)
conductor init        # connect to a project and configure local sync path
conductor start       # start the local file sync daemon
```

After `init`, issues sync to `~/.conductor/{projectId}/issues/` and Claude Code can read them directly via the MCP server.

## Commands

| Command | Description |
|---------|-------------|
| `conductor login` | Authenticate via browser. Stores credentials in `~/.conductor/config.json`. |
| `conductor logout` | Clear stored credentials. |
| `conductor init` | Connect the current directory to a Conductor project and set up Claude Code MCP integration. |
| `conductor start` | Start the background sync daemon (file watcher with 500ms debounce). |
| `conductor stop` | Stop the sync daemon. |
| `conductor status` | Show sync daemon status and current project config. |
| `conductor doctor` | Diagnose config, auth, and connectivity issues. |
| `conductor issue list` | List all issues in the project. |
| `conductor issue create --title "..." --type PRD` | Create a new issue (`PRD`, `FEATURE_REQUEST`, `BUG_REPORT`). |
| `conductor doc pull <issueId>` | Download a document draft to the local sync path. |
| `conductor doc push <issueId>` | Upload a local document draft to the cloud. |
| `conductor mcp` | Start the MCP server (stdio transport) — used internally by Claude Code. |

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
