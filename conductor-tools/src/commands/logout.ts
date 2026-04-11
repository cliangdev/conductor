import { Command } from 'commander'
import { clearConfig } from '../lib/config.js'

export function registerLogout(program: Command): void {
  program
    .command('logout')
    .description('Clear saved Conductor credentials')
    .action(() => {
      clearConfig()
      console.log('Logged out.')
    })
}
