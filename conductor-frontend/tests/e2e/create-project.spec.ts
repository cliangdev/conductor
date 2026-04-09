import { test, expect } from '@playwright/test'

test('create a new project and land on project page', async ({ page }) => {
  await page.goto('/app/projects/new')

  await expect(page.locator('#project-name')).toBeVisible()

  const projectName = `E2E Project ${Date.now()}`
  await page.fill('#project-name', projectName)
  await page.fill('#project-description', 'Created by Playwright E2E test')

  await page.click('button[type="submit"]')

  await page.waitForURL(url => !url.pathname.includes('/new'), { timeout: 15000 })
  await expect(page).not.toHaveURL(/\/new/)
})
