const doc = `## What it does

Connects an external **MCP server** (streamable HTTP) so Conductor agents can call its tools. Nothing is exposed until you tick specific tools on an agent.

## How authentication works

Enter the server URL (https only) and a bearer **API key**. Use a **read-only key** with the narrowest scope the server allows. The key is stored encrypted and never re-displayed. Conductor tests the connection as it saves and tells you if the key is rejected or the URL is not an MCP server.

## How Conductor uses it

Open an agent, then **Tools**: each connected server has its own heading. Tick only the tools the agent needs.
`

export default doc
