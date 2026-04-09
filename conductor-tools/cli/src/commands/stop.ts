import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { Command } from 'commander'

const CONDUCTOR_DIR = path.join(os.homedir(), '.conductor')
export const PID_FILE = path.join(CONDUCTOR_DIR, 'daemon.pid')

export function registerStop(program: Command): void {
  program
    .command('stop')
    .description('Stop the file watcher daemon')
    .action(() => {
      if (!fs.existsSync(PID_FILE)) {
        console.log('Daemon is not running')
        return
      }

      const pid = parseInt(fs.readFileSync(PID_FILE, 'utf8').trim(), 10)

      try {
        process.kill(pid, 'SIGTERM')
        fs.unlinkSync(PID_FILE)
        console.log(`Daemon stopped (PID ${pid})`)
      } catch {
        fs.unlinkSync(PID_FILE)
        console.log('Daemon was not running (removed stale PID file)')
      }
    })
}
