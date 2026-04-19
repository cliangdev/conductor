import { describe, it, expect, vi } from 'vitest'
import { Command } from 'commander'

const DEPRECATED_MSG =
  'The issue and doc commands have been removed. Use Claude Code with the Conductor MCP server instead.'

function makeDeprecatedIssueCommand(): Command {
  const program = new Command()
  program.exitOverride()

  program
    .command('issue', { hidden: true })
    .allowUnknownOption()
    .action(() => {
      console.log(DEPRECATED_MSG)
      process.exit(1)
    })

  return program
}

describe('issue command deprecation stub', () => {
  it('prints deprecation message and exits with code 1', async () => {
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const program = makeDeprecatedIssueCommand()
    await program.parseAsync(['node', 'conductor', 'issue'])

    expect(consoleSpy).toHaveBeenCalledWith(DEPRECATED_MSG)
    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('prints deprecation message for subcommands and exits with code 1', async () => {
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const program = makeDeprecatedIssueCommand()
    await program.parseAsync(['node', 'conductor', 'issue', 'list'])

    expect(consoleSpy).toHaveBeenCalledWith(DEPRECATED_MSG)
    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})
