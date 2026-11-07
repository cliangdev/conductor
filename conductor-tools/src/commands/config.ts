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

function redactToken(token: string | undefined): string {
  if (!token) return 'not set'
  return redactApiKey(token)
}

export function registerConfig(program: Command): void {
  const config = program
    .command('config')
    .description('Manage CLI configuration')
    .addHelpText('after', `
Examples:
  conductor config show
  conductor config show --json
  conductor config use prod
  conductor config set-url https://my-backend.example.com`)

  config
    .command('show')
    .description('Display current configuration')
    .option('--json', 'Output config as JSON (apiKey is redacted)')
    .addHelpText('after', `
Examples:
  conductor config show
  conductor config show --json`)
    .action((options: { json?: boolean }) => {
      const cfg = readConfig()
      if (!cfg) {
        console.error('No config found — run conductor login first')
        process.exit(1)
        return
      }

      if (options.json) {
        const output = {
          apiUrl: cfg.apiUrl,
          frontendUrl: cfg.frontendUrl,
          projectId: cfg.projectId,
          projectName: cfg.projectName,
          email: cfg.email,
          apiKey: redactApiKey(cfg.apiKey),
          claudeCodeOauthToken: redactToken(cfg.claudeCodeOauthToken),
          openaiApiKey: redactToken(cfg.openaiApiKey),
          videoRoute: cfg.videoRoute ?? 'auto',
          vertexProject: cfg.vertexProject ?? 'not set',
          vertexRegion: cfg.vertexRegion ?? 'us-central1',
          geminiApiKey: redactToken(cfg.geminiApiKey),
          videoPerCallCapUsd: cfg.videoPerCallCapUsd ?? 2,
          videoDailyCapUsd: cfg.videoDailyCapUsd ?? 5,
          ...(cfg.videoRates ? { videoRates: cfg.videoRates } : {}),
        }
        process.stdout.write(JSON.stringify(output, null, 2) + '\n')
        process.exit(0)
        return
      }

      console.log(`apiUrl:      ${cfg.apiUrl}`)
      console.log(`projectId:   ${cfg.projectId}`)
      console.log(`projectName: ${cfg.projectName}`)
      console.log(`email:       ${cfg.email}`)
      console.log(`apiKey:      ${redactApiKey(cfg.apiKey)}`)
      console.log(`claudeCodeOauthToken: ${redactToken(cfg.claudeCodeOauthToken)}`)
      console.log(`openaiApiKey: ${redactToken(cfg.openaiApiKey)}`)
      console.log(`videoRoute: ${cfg.videoRoute ?? 'auto'}`)
      console.log(`vertexProject: ${cfg.vertexProject ?? 'not set'}`)
      console.log(`vertexRegion: ${cfg.vertexRegion ?? 'us-central1'}`)
      console.log(`geminiApiKey: ${redactToken(cfg.geminiApiKey)}`)
      console.log(`videoPerCallCapUsd: ${cfg.videoPerCallCapUsd ?? 2}`)
      console.log(`videoDailyCapUsd: ${cfg.videoDailyCapUsd ?? 5}`)
    })

  config
    .command('use <env>')
    .description('Switch to a named environment (prod)')
    .addHelpText('after', `
Examples:
  conductor config use prod`)
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
    .addHelpText('after', `
Examples:
  conductor config set-url https://conductor-backend-199707291514.us-central1.run.app
  conductor config set-url http://localhost:8080`)
    .action((url: string) => {
      if (!url.startsWith('http://') && !url.startsWith('https://')) {
        console.error('Invalid URL: must start with http:// or https://')
        process.exit(2)
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

  config
    .command('set-claude-code-oauth-token <token>')
    .description('Store a Claude Code subscription token (from `claude setup-token`) for the claude-code workflow step')
    .addHelpText('after', `
Examples:
  conductor config set-claude-code-oauth-token sk-ant-oat01-...`)
    .action((token: string) => {
      const cfg = readConfig()
      if (!cfg) {
        console.error('No config found — run conductor login first')
        process.exit(1)
        return
      }
      cfg.claudeCodeOauthToken = token
      writeConfig(cfg)
      console.log(`Claude Code OAuth token saved: ${redactToken(token)}`)
    })

  config
    .command('unset-claude-code-oauth-token')
    .description('Remove the stored Claude Code subscription token')
    .action(() => {
      const cfg = readConfig()
      if (!cfg) {
        console.error('No config found — run conductor login first')
        process.exit(1)
        return
      }
      delete cfg.claudeCodeOauthToken
      writeConfig(cfg)
      console.log('Claude Code OAuth token removed.')
    })

  config
    .command('set-openai-api-key <key>')
    .description('Store an OpenAI API key for generate_creative_image (about $0.05 an image, billed to that key)')
    .addHelpText('after', `
Examples:
  conductor config set-openai-api-key sk-...`)
    .action((key: string) => {
      const cfg = readConfig()
      if (!cfg) {
        console.error('No config found — run conductor login first')
        process.exit(1)
        return
      }
      cfg.openaiApiKey = key
      writeConfig(cfg)
      console.log(`OpenAI API key saved: ${redactToken(key)}`)
    })

  config
    .command('unset-openai-api-key')
    .description('Remove the stored OpenAI API key')
    .action(() => {
      const cfg = readConfig()
      if (!cfg) {
        console.error('No config found — run conductor login first')
        process.exit(1)
        return
      }
      delete cfg.openaiApiKey
      writeConfig(cfg)
      console.log('OpenAI API key removed.')
    })

  const withConfig = (fn: (cfg: NonNullable<ReturnType<typeof readConfig>>) => void) => {
    const cfg = readConfig()
    if (!cfg) {
      console.error('No config found — run conductor login first')
      process.exit(1)
      return
    }
    fn(cfg)
  }

  config
    .command('set-vertex-project <id>')
    .description('Set the Google Cloud project billed for Veo video on Vertex AI (also run `gcloud auth login`)')
    .action((id: string) => {
      withConfig((cfg) => {
        cfg.vertexProject = id.trim()
        writeConfig(cfg)
        console.log(`Vertex project set: ${cfg.vertexProject}`)
      })
    })

  config
    .command('set-vertex-region <region>')
    .description('Set the Vertex AI region for video (default us-central1, the only documented one)')
    .action((region: string) => {
      withConfig((cfg) => {
        cfg.vertexRegion = region.trim()
        writeConfig(cfg)
        console.log(`Vertex region set: ${cfg.vertexRegion}`)
      })
    })

  config
    .command('set-gemini-api-key <key>')
    .description('Store a Gemini API key for generate_creative_video on Gemini Omni (billed to that key)')
    .action((key: string) => {
      withConfig((cfg) => {
        cfg.geminiApiKey = key
        writeConfig(cfg)
        console.log(`Gemini API key saved: ${redactToken(key)}`)
      })
    })

  config
    .command('unset-gemini-api-key')
    .description('Remove the stored Gemini API key')
    .action(() => {
      withConfig((cfg) => {
        delete cfg.geminiApiKey
        writeConfig(cfg)
        console.log('Gemini API key removed.')
      })
    })

  config
    .command('set-video-route <route>')
    .description('Choose the video route: vertex (Veo) or gemini (Omni)')
    .action((route: string) => {
      if (route !== 'vertex' && route !== 'gemini') {
        console.error('Invalid route: use vertex or gemini')
        process.exit(2)
        return
      }
      withConfig((cfg) => {
        cfg.videoRoute = route
        writeConfig(cfg)
        console.log(`Video route set: ${route}`)
      })
    })

  config
    .command('set-video-caps')
    .description('Set the video spend caps in USD: per call (default 2) and per day (default 5)')
    .option('--per-call <usd>', 'Refuse a single call estimated above this')
    .option('--daily <usd>', 'Refuse calls once today\'s estimated spend would pass this')
    .action((options: { perCall?: string; daily?: string }) => {
      const parse = (label: string, v: string | undefined): number | undefined | null => {
        if (v === undefined) return undefined
        const n = Number(v)
        if (!Number.isFinite(n) || n <= 0) {
          console.error(`Invalid ${label}: must be a positive number of USD`)
          return null
        }
        return n
      }
      const perCall = parse('--per-call', options.perCall)
      const daily = parse('--daily', options.daily)
      if (perCall === null || daily === null) {
        process.exit(2)
        return
      }
      if (perCall === undefined && daily === undefined) {
        console.error('Pass --per-call <usd> and/or --daily <usd>')
        process.exit(2)
        return
      }
      withConfig((cfg) => {
        if (perCall !== undefined) cfg.videoPerCallCapUsd = perCall
        if (daily !== undefined) cfg.videoDailyCapUsd = daily
        writeConfig(cfg)
        console.log(`Video caps: $${cfg.videoPerCallCapUsd ?? 2} per call, $${cfg.videoDailyCapUsd ?? 5} per day`)
      })
    })
}
