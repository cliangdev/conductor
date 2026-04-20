import chalk from 'chalk'

export function printNextSteps(): void {
  console.log()
  console.log(chalk.bold('Next steps:'))
  console.log()
  console.log(`  1. Open Claude Code in this directory`)
  console.log(`  2. Create your first PRD       ${chalk.cyan('/conductor:prd')}`)
  console.log()
}
