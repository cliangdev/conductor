import * as os from 'os'
import * as path from 'path'
import { spawnSync, type SpawnSyncReturns } from 'child_process'
import { Command } from 'commander'
import chalk from 'chalk'
import { clearCache, PACKAGE_NAME } from '../lib/update-check.js'
import { getAssetSrcDir, getPluginInstallStatus } from '../lib/plugin-assets.js'

export interface UpgradeDeps {
  spawn: (cmd: string, args: string[], opts: { stdio: 'inherit' }) => Pick<SpawnSyncReturns<Buffer>, 'status' | 'error'>
  log: (msg: string) => void
  errLog: (msg: string) => void
  cwd: string
  home: string
  clearCache: () => void
  pluginInstalled: (globalDir: string, localDir: string) => boolean
}

function defaults(): UpgradeDeps {
  return {
    spawn: (cmd, args, opts) => spawnSync(cmd, args, opts),
    log: (m) => console.log(m),
    errLog: (m) => console.error(m),
    cwd: process.cwd(),
    home: os.homedir(),
    clearCache: () => clearCache(),
    pluginInstalled: (g, l) => getPluginInstallStatus(getAssetSrcDir(), g, l).location !== 'none',
  }
}

function permissionHint(): string {
  return ' npm could not write to its global directory. Fix it without sudo by using a Node version manager (nvm, fnm, volta) or an npm prefix you own (`npm config set prefix ~/.npm-global`), then re-run `conductor upgrade`.'
}

/** Returns the process exit code. */
export function runUpgrade(overrides: Partial<UpgradeDeps> = {}): number {
  const deps = { ...defaults(), ...overrides }
  const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
  deps.log(`Running: npm i -g ${PACKAGE_NAME}@latest`)
  const res = deps.spawn(npm, ['i', '-g', `${PACKAGE_NAME}@latest`], { stdio: 'inherit' })

  if (res.error) {
    const code = (res.error as NodeJS.ErrnoException).code
    if (code === 'ENOENT') {
      deps.errLog(chalk.red(`✗ npm was not found on your PATH. Install Node.js/npm, then run: npm i -g ${PACKAGE_NAME}@latest`))
    } else if (code === 'EACCES' || code === 'EPERM') {
      deps.errLog(chalk.red('✗ Permission denied while running npm.') + permissionHint())
    } else {
      deps.errLog(chalk.red(`✗ Could not run npm: ${res.error.message}`))
    }
    return 1
  }
  if (res.status !== 0) {
    deps.errLog(chalk.red(`✗ npm exited with status ${res.status ?? 'unknown'}.`))
    deps.errLog(`  If you saw EACCES above:${permissionHint()}`)
    return res.status ?? 1
  }

  deps.clearCache()
  deps.log(chalk.green('✓ Conductor CLI updated.'))

  // `conductor init` is interactive (login + project picker), so it is never spawned here. The new
  // version refreshes an installed plugin's commands/skills itself when `conductor mcp` or
  // `conductor start` next runs, so restarting Claude Code is enough for them.
  const installed = deps.pluginInstalled(path.join(deps.home, '.claude'), path.join(deps.cwd, '.claude'))
  if (installed) {
    deps.log("Restart Claude Code to load the new version; this repo's Conductor commands refresh automatically when it starts.")
    deps.log('Run `conductor init` only if you need to re-link a project or repair .mcp.json.')
  } else {
    deps.log('Run `conductor init` in each repo that uses Conductor to update its Claude commands.')
  }
  return 0
}

export function registerUpgrade(program: Command): void {
  program
    .command('upgrade')
    .description('Update the Conductor CLI to the latest version')
    .addHelpText('after', `
Examples:
  conductor upgrade`)
    .action(() => {
      process.exitCode = runUpgrade()
    })
}
