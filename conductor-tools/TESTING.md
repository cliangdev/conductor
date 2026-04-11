# Conductor CLI & MCP — E2E Test Procedures

## CLI: Build and Prod Test Procedure

### Prerequisites

- Node.js installed
- Access to the Conductor prod frontend URL

### Steps

1. **Build the CLI**

   ```bash
   cd conductor-tools && npm run build
   ```

2. **Link globally**

   ```bash
   npm link
   ```

3. **Verify the binary is available**

   ```bash
   conductor --version
   ```

   Expected output: `0.1.0`

4. **Authenticate against prod** — choose one option:

   **Option A — Fresh login** (sets `apiUrl` and `apiKey` in one step):
   ```bash
   CONDUCTOR_API_URL=<prod-url> conductor login
   ```

   **Option B — Already logged in locally, just switch the URL:**
   ```bash
   conductor config set-url <prod-url>
   ```

   Replace `<prod-url>` with the actual production API URL (e.g. `https://api.conductor.example.com`).

5. **Verify config points to prod**

   ```bash
   conductor config show
   ```

   Confirm `apiUrl` matches the prod URL and `apiKey` is redacted (e.g. `abcd...wxyz`).

6. **Create a test issue**

   ```bash
   conductor issue create --title "E2E Test Issue" --type PRD
   ```

   Note the issue ID printed in the output.

7. **Verify in the frontend**

   Open the Conductor frontend at the prod URL and confirm the new issue appears in the project.

8. **Run the health check**

   ```bash
   conductor doctor
   ```

   All checks should pass against prod. If any fail, the output will indicate which service or config is the problem.

9. **Clean up**

   Delete the test issue via the Conductor UI, or note its ID for tracking.

---

## MCP: Test Procedure

### Prerequisites

- CLI is built and linked globally (complete the CLI procedure above first)
- Claude Code is installed

### Steps

1. **Set the API URL to prod** (if not already done via the CLI procedure above)

   ```bash
   conductor config set-url <prod-url>
   ```

2. **Verify the MCP server binary responds**

   ```bash
   conductor mcp
   ```

   Expected: prints something like `MCP server started` on stderr and waits (stdio transport). Press `Ctrl+C` to exit.

3. **Register MCP in the project** (if not already done via `conductor init`)

   Create or update `.mcp.json` in the project root:

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

4. **Open Claude Code** in the project directory containing `.mcp.json`

   Claude Code will detect and connect to the MCP server on startup.

5. **Create a test issue via Claude**

   In the Claude Code chat, send:

   > Create a PRD issue titled "MCP Test — please delete"

   Claude will invoke the MCP tool. Confirm it responds with a success message and an issue ID.

6. **Verify in the frontend**

   Open the Conductor frontend at the prod URL and confirm the issue appears in the project.

7. **Clean up**

   Delete the test issue via the Conductor UI.

### How MCP resolves configuration

The MCP server reads `apiUrl` and `apiKey` directly from `~/.conductor/config.json` — the same file the CLI writes. After running `conductor config set-url`, the new URL is used immediately with no MCP restart required.
