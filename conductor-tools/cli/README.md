# @conductor/cli

CLI for managing Conductor issues and syncing documents locally.

## Setup

```bash
npm install
npm run build
npm link          # makes `conductor` available globally
conductor --version
```

## Local Dev vs Prod

### Local dev (`make dev`)

The local stack runs at `http://localhost:8080` (backend) and `http://localhost:3000` (frontend). It uses email/password auth — no Firebase/Google required.

```bash
# First time: login with email/password
CONDUCTOR_API_URL=http://localhost:8080 conductor login --local
# Prompts for email and password (default: dev@example.com / conductor)
# Writes ~/.conductor/config.json automatically

# Verify
conductor config show
conductor doctor
```

If you've already logged in to prod and want to switch to local:

```bash
conductor config set-url http://localhost:8080
```

Switch back to prod when done:

```bash
conductor config set-url <prod-url>
```

### Prod (Cloud Run)

Prod uses Google OAuth via a browser flow.

```bash
# First time: opens browser for Google sign-in
CONDUCTOR_API_URL=<prod-url> conductor login

# Already logged in locally — just swap the URL
conductor config set-url <prod-url>

# Verify
conductor config show   # apiUrl should show prod URL
conductor doctor        # all checks should pass
```

## Commands

| Command | Description |
|---------|-------------|
| `conductor login` | Authenticate via browser (Google OAuth, prod) |
| `conductor login --local` | Authenticate via email/password (local dev only) |
| `conductor config show` | Print current config (apiKey is redacted) |
| `conductor config set-url <url>` | Hot-swap API URL without re-auth |
| `conductor issue create` | Create a new issue |
| `conductor issue list` | List issues in the project |
| `conductor doctor` | Check config and API connectivity |
| `conductor init` | Initialize MCP config in current project |
| `conductor mcp` | Start the MCP server (stdio transport) |

## E2E Testing

See [TESTING.md](./TESTING.md) for the full step-by-step test procedure for both CLI and MCP against prod.
