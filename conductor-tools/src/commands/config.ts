import { Command } from 'commander'
import { readConfig, writeConfig } from '../lib/config.js'

interface Preset {
  apiUrl: string
  frontendUrl: string
}

const PRESETS: Record<string, Preset> = {
  prod: {
    apiUrl: 'https://conductor-backend-199707291514.us-central1.run.app',
    frontendUrl: 'https://conductor-frontend-199707291514.us-central1.run.app',
  },
}

function redactApiKey(apiKey: string): string {
  if (apiKey.length <= 8) return '***'
  return `${apiKey.slice(0, 4)}...${apiKey.slice(-4)}`
}

export function registerConfig(program: Command): void {
  const config = program.command('config').description('Manage CLI configuration')

  config
    .command('show')
    .description('Display current configuration')
    .action(() => {
      const cfg = readConfig()
      if (!cfg) {
        console.error('No config found — run conductor login first')
        process.exit(1)
        return
      }
      console.log(`apiUrl:      ${cfg.apiUrl}`)
      console.log(`projectId:   ${cfg.projectId}`)
      console.log(`projectName: ${cfg.projectName}`)
      console.log(`email:       ${cfg.email}`)
      console.log(`apiKey:      ${redactApiKey(cfg.apiKey)}`)
    })

  config
    .command('use <env>')
    .description('Switch to a named environment (prod)')
    .action((env: string) => {
      const preset = PRESETS[env]
      if (!preset) {
        console.error(`Unknown environment: ${env}. Available: ${Object.keys(PRESETS).join(', ')}`)
        process.exit(1)
        return
      }
      const existing = readConfig()
      const cfg = existing ?? { apiKey: '', projectId: '', projectName: '', email: '', apiUrl: '', frontendUrl: '' }
      cfg.apiUrl = preset.apiUrl
      cfg.frontendUrl = preset.frontendUrl
      writeConfig(cfg)
      console.log(`Switched to ${env}:`)
      console.log(`  backend:  ${preset.apiUrl}`)
      console.log(`  frontend: ${preset.frontendUrl}`)
      if (!existing) {
        console.log(`\nRun conductor login to authenticate.`)
      }
    })

  config
    .command('set-url <url>')
    .description('Update the API URL')
    .action((url: string) => {
      if (!url.startsWith('http://') && !url.startsWith('https://')) {
        console.error('Invalid URL: must start with http:// or https://')
        process.exit(1)
        return
      }
      const cfg = readConfig()
      if (!cfg) {
        console.error('No config found — run conductor login first')
        process.exit(1)
        return
      }
      cfg.apiUrl = url
      writeConfig(cfg)
      console.log(`API URL updated to: ${url}`)
    })
}
