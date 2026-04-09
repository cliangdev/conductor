import { test as setup } from '@playwright/test'

const authFile = 'tests/e2e/.auth/user.json'

setup('authenticate', async ({ page }) => {
  await page.goto('/login')

  await page.fill('#email', 'playwright@example.com')
  await page.fill('#password', 'conductor')
  await page.click('button[type="submit"]')

  await page.waitForURL('**/app/projects**', { timeout: 15000 })

  await page.context().storageState({ path: authFile })
})
