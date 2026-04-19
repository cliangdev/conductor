#!/usr/bin/env node
// Runs before npm uninstall. Cleans up conductor plugin assets and MCP entries.

import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

const HOME = os.homedir()
const CONDUCTOR_DIR = path.join(HOME, '.conductor')
const PID_FILE = path.join(CONDUCTOR_DIR, 'daemon.pid')
const CLAUDE_DIR = path.join(HOME, '.claude')
const SETTINGS_JSON = path.join(CLAUDE_DIR, 'settings.json')
const MCP_JSON = path.join(process.cwd(), '.mcp.json')

const SIGTERM_WAIT_MS = 3000
const SIGTERM_POLL_MS = 100

function isProcessRunning(pid) {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

function wait(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

export async function stopDaemon() {
  try {
    let pidStr
    try {
      pidStr = fs.readFileSync(PID_FILE, 'utf8').trim()
    } catch {
      return
    }

    const pid = parseInt(pidStr, 10)
    if (isNaN(pid)) return

    try {
      process.kill(pid, 'SIGTERM')
    } catch {
      return
    }

    const deadline = Date.now() + SIGTERM_WAIT_MS
    while (Date.now() < deadline && isProcessRunning(pid)) {
      await wait(SIGTERM_POLL_MS)
    }
  } catch (err) {
    console.warn('conductor preuninstall warning (stopDaemon):', err.message)
  }
}

export function removeClaudeAssets() {
  const commandsDir = path.join(CLAUDE_DIR, 'commands', 'conductor')
  try {
    if (fs.existsSync(commandsDir)) {
      fs.rmSync(commandsDir, { recursive: true, force: true })
    }
  } catch (err) {
    console.warn('conductor preuninstall warning (commands):', err.message)
  }

  const agentsDir = path.join(CLAUDE_DIR, 'agents')
  try {
    if (fs.existsSync(agentsDir)) {
      const entries = fs.readdirSync(agentsDir)
      for (const entry of entries) {
        if (entry.startsWith('conductor')) {
          try {
            fs.rmSync(path.join(agentsDir, entry), { recursive: true, force: true })
          } catch (err) {
            console.warn('conductor preuninstall warning (agents):', err.message)
          }
        }
      }
    }
  } catch (err) {
    console.warn('conductor preuninstall warning (agents dir):', err.message)
  }

  const skillsDir = path.join(CLAUDE_DIR, 'skills')
  try {
    if (fs.existsSync(skillsDir)) {
      const entries = fs.readdirSync(skillsDir)
      for (const entry of entries) {
        if (entry.startsWith('conductor')) {
          try {
            fs.rmSync(path.join(skillsDir, entry), { recursive: true, force: true })
          } catch (err) {
            console.warn('conductor preuninstall warning (skills):', err.message)
          }
        }
      }
    }
  } catch (err) {
    console.warn('conductor preuninstall warning (skills dir):', err.message)
  }
}

export function cleanSettings() {
  try {
    let raw
    try {
      raw = fs.readFileSync(SETTINGS_JSON, 'utf8')
    } catch {
      return
    }

    let settings
    try {
      settings = JSON.parse(raw)
    } catch {
      return
    }

    if (Array.isArray(settings.allow)) {
      settings.allow = settings.allow.filter(entry => !String(entry).startsWith('mcp__conductor__'))
    }

    fs.writeFileSync(SETTINGS_JSON, JSON.stringify(settings, null, 2), 'utf8')
  } catch (err) {
    console.warn('conductor preuninstall warning (settings.json):', err.message)
  }
}

export function cleanMcpJson() {
  try {
    let raw
    try {
      raw = fs.readFileSync(MCP_JSON, 'utf8')
    } catch {
      return
    }

    let config
    try {
      config = JSON.parse(raw)
    } catch {
      return
    }

    if (config.mcpServers && 'conductor' in config.mcpServers) {
      delete config.mcpServers.conductor
    }

    fs.writeFileSync(MCP_JSON, JSON.stringify(config, null, 2), 'utf8')
  } catch (err) {
    console.warn('conductor preuninstall warning (.mcp.json):', err.message)
  }
}

async function main() {
  await stopDaemon()
  removeClaudeAssets()
  cleanSettings()
  cleanMcpJson()

  console.log('Conductor plugin assets removed from ~/.claude/')
  console.log('Conductor MCP entries removed from settings.json and .mcp.json')
  console.log('Your project data has been kept at ~/.conductor/')
  console.log('To fully remove all data: rm -rf ~/.conductor')
}

// Only run main when executed directly (not imported by tests)
const isMain = process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)
if (isMain) {
  main().catch(err => {
    console.warn('conductor preuninstall warning:', err.message)
  })
}
