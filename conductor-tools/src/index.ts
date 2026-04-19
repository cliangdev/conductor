#!/usr/bin/env node
import { Command } from 'commander'
import { registerLogin } from './commands/login.js'
import { registerLogout } from './commands/logout.js'
import { registerInit } from './commands/init.js'
import { registerDoctor } from './commands/doctor.js'
import { registerStart } from './commands/start.js'
import { registerStop } from './commands/stop.js'
import { registerMcp } from './commands/mcp.js'
import { registerConfig } from './commands/config.js'
import { registerDashboard } from './commands/dashboard.js'
import { registerStatus } from './commands/status.js'

const DEPRECATED_MSG = 'The issue and doc commands have been removed. Use Claude Code with the Conductor MCP server instead.'

const program = new Command()

program
  .name('conductor')
  .description('Conductor CLI for project setup and MCP integration')
  .version('0.1.0')

registerLogin(program)
registerLogout(program)
registerInit(program)
registerDoctor(program)
registerStart(program)
registerStop(program)
registerMcp(program)
registerConfig(program)
registerDashboard(program)
registerStatus(program)

program
  .command('issue', { hidden: true })
  .allowUnknownOption()
  .action(() => {
    console.log(DEPRECATED_MSG)
    process.exit(1)
  })

program
  .command('doc', { hidden: true })
  .allowUnknownOption()
  .action(() => {
    console.log(DEPRECATED_MSG)
    process.exit(1)
  })

program.parse()
