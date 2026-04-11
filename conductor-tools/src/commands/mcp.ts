import { Command } from 'commander'

export function registerMcp(program: Command): void {
  program
    .command('mcp')
    .description('Start the MCP stdio server for Claude Code integration')
    .action(async () => {
      const { runMcpServer } = await import('../mcp/index.js')
      await runMcpServer()
    })
}
