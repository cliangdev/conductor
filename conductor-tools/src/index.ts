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

program.parse()
