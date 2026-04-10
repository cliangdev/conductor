#!/usr/bin/env node
import { Command } from 'commander'
import { registerLogin } from './commands/login.js'
import { registerLogout } from './commands/logout.js'
import { registerInit } from './commands/init.js'
import { registerStatus } from './commands/status.js'
import { registerDoctor } from './commands/doctor.js'
import { registerIssue } from './commands/issue.js'
import { registerDoc } from './commands/doc.js'
import { registerStart } from './commands/start.js'
import { registerStop } from './commands/stop.js'

const program = new Command()

program
  .name('conductor')
  .description('Conductor CLI for project setup and issue management')
  .version('0.1.0')

registerLogin(program)
registerLogout(program)
registerInit(program)
registerStatus(program)
registerDoctor(program)
registerIssue(program)
registerDoc(program)
registerStart(program)
registerStop(program)

program.parse()
