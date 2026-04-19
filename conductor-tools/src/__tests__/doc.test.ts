import { describe, it, expect, vi } from 'vitest'
import { Command } from 'commander'

const DEPRECATED_MSG =
  'The issue and doc commands have been removed. Use Claude Code with the Conductor MCP server instead.'

function makeDeprecatedDocCommand(): Command {
  const program = new Command()
  program.exitOverride()

  program
    .command('doc', { hidden: true })
    .allowUnknownOption()
    .action(() => {
      console.log(DEPRECATED_MSG)
      process.exit(1)
    })

  return program
}

describe('doc command deprecation stub', () => {
  it('prints deprecation message and exits with code 1', async () => {
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const program = makeDeprecatedDocCommand()
    await program.parseAsync(['node', 'conductor', 'doc'])

    expect(consoleSpy).toHaveBeenCalledWith(DEPRECATED_MSG)
    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('prints deprecation message for subcommands and exits with code 1', async () => {
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const program = makeDeprecatedDocCommand()
    await program.parseAsync(['node', 'conductor', 'doc', 'pull', 'iss_123'])

    expect(consoleSpy).toHaveBeenCalledWith(DEPRECATED_MSG)
    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})
