# Dev Workflow

Practical guide for deploying, testing, and debugging changes on a PR branch.

## PR Branch Deployment

CI deploys to Cloud Run when you add a label to the PR:

| Label | Effect |
|-------|--------|
| `deploy-backend` | Builds, tests, and deploys `conductor-backend` to Cloud Run |
| `deploy-frontend` | Builds, tests, and deploys `conductor-frontend` to Cloud Run |
| `skip-tests` | Skips the test step in the deploy preview workflow — deploy goes straight to Cloud Run |

**To redeploy after new commits:** remove then re-add the deploy label.

**`skip-tests` rule:** Add it freely during active debugging to cut deploy time. Remove it before the PR is ready to merge. The `backend-ci.yml` workflow (required merge gate) always runs the full test suite regardless of this label.

## Testing Against the Live Environment

Live deploys surface integration bugs that unit tests miss — missing API fields, null guards, executor dispatch wiring. Prefer the deploy-and-test loop over a local stack whenever a change touches API contracts, request/response mapping, or execution paths.

### End-to-End MCP Tool Testing

When testing new MCP tools, call them in sequence rather than stopping at unit tests. Follow the action → verify pattern:

```
create  → get (verify stored correctly)
       → publish
       → dispatch
       → get_run (confirm actual execution, not just acceptance)
```

A `202 Accepted` on dispatch does not mean the run succeeded — always call `get_run` to check the step-level result.

## conductor-tools (CLI + MCP server)

The `.mcp.json` MCP server entry uses the globally installed `conductor` binary by default. During development on `conductor-tools`, point it at the local build instead:

```json
{
  "mcpServers": {
    "conductor": {
      "command": "node",
      "args": ["/absolute/path/to/conductor-tools/dist/index.js", "mcp"]
    }
  }
}
```

**After any change to conductor-tools:**
1. `cd conductor-tools && npm run build`
2. Restart Claude Code — the MCP server is loaded at session start

New skill files added to `.claude/commands/` also require a Claude Code restart to register.

## Debugging Live Backend Errors

Check Cloud Run logs for 500s and exceptions:

```bash
export CONDUCTOR_GCP_PROJECT=<project>
./scripts/logs.sh --since 5m          # last 5 minutes of backend logs
./scripts/logs.sh frontend --since 5m # frontend logs
```

See `scripts/gcloud-alias-example.sh` for a persistent shell alias.

## Checking CI Status

```bash
gh pr checks <PR#> --json name,state,workflow
```

Check PR comments for deploy notifications — CI posts a `✅ Backend deployment succeeded` or `❌ failed` comment with a link to the run logs.
