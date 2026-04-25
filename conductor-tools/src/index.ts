#!/usr/bin/env node
import { createRequire } from 'module'
import { Command } from 'commander'

const require = createRequire(import.meta.url)
const { version } = require('../package.json')
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
import { registerLint } from './commands/lint.js'

const DEPRECATED_MSG = 'The issue and doc commands have been removed. Use Claude Code with the Conductor MCP server instead.'

const program = new Command()

program
  .name('conductor')
  .description('Conductor CLI for project setup and MCP integration')
  .version(version)

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
registerLint(program)

program
  .command('issue', { hidden: true })
  .allowUnknownOption()
  .allowExcessArguments()
  .action(() => {
    console.log(DEPRECATED_MSG)
    process.exit(1)
  })

program
  .command('doc', { hidden: true })
  .allowUnknownOption()
  .allowExcessArguments()
  .action(() => {
    console.log(DEPRECATED_MSG)
    process.exit(1)
  })

program.parse()
