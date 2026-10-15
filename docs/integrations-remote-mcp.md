# Remote MCP server connector

The `remote-mcp` connector lets Conductor agents call the tools of any external [MCP](https://modelcontextprotocol.io) server over streamable HTTP, authenticated with a bearer key. It is generic: nothing in it is specific to one product. Typical use is giving an agent read access to a product's admin or analytics server.

## Setup

1. Integrations → **Remote MCP server** → Connect. Enter the server URL (https only) and an API key.
2. Conductor connects as it saves: it runs `initialize` and `tools/list`. A bad URL, a rejected key (401/403), an unreachable host or a non-MCP response fails the connect with a plain message. On success the tool count is reported.
3. Add as many connections as you have servers; each is its own group in the agent tool picker.
4. Agents → edit an agent → **Tools**: tick the specific tools the agent may use. Tools appear under a heading per connection.

Tools only reach agents that list them in `toolIds`. Connecting a server exposes nothing on its own.

## How it works

- Backend client: `integration/connector/mcp/McpClient` (a thin hand-written client rather than the official Java SDK, to keep control of timeouts, redirects and the SSRF guard). It POSTs JSON-RPC 2.0 with `Accept: application/json, text/event-stream`, reads a JSON or SSE reply, and echoes `Mcp-Session-Id`. It re-initialises once if the server forgets the session (404). Timeouts: 10 s to connect, 30 s per call.
- Agent tools: `agent/tool/RemoteMcpToolProvider` (source `mcp`). Tool id `mcp:{connectionId}/{toolName}`; model-facing name `{slug}_{toolName}` where the slug is the first 6 alphanumerics of the connection id, sanitised to `^[a-zA-Z0-9_-]{1,64}$` (a hash suffix keeps clipped or rewritten names unique and stable).
- Tool lists are cached per connection for 10 minutes (1 minute after a failure). A server that is down contributes no tools and a WARN log; the rest of the list is unaffected.
- The model's arguments pass straight through to `tools/call`. Text parts of the result become the payload; `isError: true` becomes a tool error carrying the server's text. Payloads are cut at 8,000 bytes.
- `GET /projects/{id}/agents/tools` returns each MCP tool with `source: "mcp"` and `group` set to the connection label (or the server host).
- Claude Code runtime steps cannot use these tools (there is no Claude Code name for them) and fail clearly.

## Security notes

- Use a **read-only key** scoped as narrowly as the server allows, and tick only the tools the agent needs. Conductor cannot tell a read tool from a write tool; the key is the real boundary.
- The key is stored encrypted like every connector credential, sent only in the `Authorization` header, and never logged. Arguments are not logged at INFO.
- URLs must be https and must not resolve to loopback, private (RFC 1918), link-local (including `169.254.169.254`), carrier-grade NAT or unique-local addresses (`integration/OutboundUrlGuard`). Redirects are never followed. The `local` profile allows http and private hosts for development. The guard resolves the name when it checks; a hostname that flips to an internal address between check and connect is a residual DNS-rebinding risk.
- Tool results come from a third party and are shown to the model: treat them as untrusted input.
