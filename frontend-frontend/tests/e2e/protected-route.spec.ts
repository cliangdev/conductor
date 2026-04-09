import { test, expect } from '@playwright/test'

test.use({ storageState: { cookies: [], origins: [] } })

test('unauthenticated access to projects redirects to login', async ({ page }) => {
  await page.goto('/app/projects')

  await page.waitForURL('**/login**', { timeout: 10000 })
  await expect(page).toHaveURL(/\/login/)

  await expect(page.locator('#email')).toBeVisible()
})
