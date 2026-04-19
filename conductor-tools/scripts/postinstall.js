#!/usr/bin/env node
// Runs after npm install. Only prompts when installed globally with a TTY.

import { fileURLToPath } from 'url'
import * as path from 'path'
import * as os from 'os'
import * as readline from 'readline'

const isGlobal = process.env['npm_config_global'] === 'true'
const isTTY = Boolean(process.stdin.isTTY)

function printWhatsNext() {
  console.log()
  console.log("What's next:")
  console.log('  Run: conductor login')
}

async function main() {
  try {
    if (!isGlobal || !isTTY) {
      printWhatsNext()
      process.exit(0)
    }

    const __filename = fileURLToPath(import.meta.url)
    const __dirname = path.dirname(__filename)

    let pluginModule
    try {
      pluginModule = await import(path.join(__dirname, '..', 'dist', 'lib', 'plugin-assets.js'))
    } catch {
      // dist/ not available (e.g. local dev clone without build) — skip silently
      printWhatsNext()
      process.exit(0)
    }

    const { installPluginAssets, getAssetSrcDir } = pluginModule

    function askYesNo(question) {
      const rl = readline.createInterface({ input: process.stdin, output: process.stdout })
      return new Promise(resolve => {
        rl.question(question, answer => {
          rl.close()
          resolve(answer.trim().toLowerCase() !== 'n')
        })
      })
    }

    console.log()
    const yes = await askYesNo('Install conductor Claude plugin globally? [Y/n] ')

    if (!yes) {
      console.log('  Run `conductor init` in any project to install locally.')
      printWhatsNext()
      process.exit(0)
    }

    const targetDir = path.join(os.homedir(), '.claude')
    const assetSrcDir = getAssetSrcDir()
    const mcpJsonPath = path.join(os.homedir(), '.claude', '.mcp.json')

    const status = installPluginAssets(targetDir, assetSrcDir, mcpJsonPath)

    if (status === 'installed') {
      console.log('✓ Installed conductor Claude plugin globally')
    } else if (status === 'updated') {
      console.log('✓ Updated conductor Claude plugin globally')
    } else {
      console.log('✓ Conductor Claude plugin up to date')
    }

    printWhatsNext()
    process.exit(0)
  } catch (err) {
    console.warn('conductor postinstall warning:', err.message)
    printWhatsNext()
    process.exit(0)
  }
}

main()
