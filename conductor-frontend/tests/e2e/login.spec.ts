import { test, expect } from '@playwright/test'

test.use({ storageState: { cookies: [], origins: [] } })

test('local login form renders and authenticates', async ({ page }) => {
  await page.goto('/login')

  await expect(page.locator('#email')).toBeVisible()
  await expect(page.locator('#password')).toBeVisible()
  await expect(page.locator('button[type="submit"]')).toBeVisible()

  await expect(page.getByText('Sign in with Google')).not.toBeVisible()

  await page.fill('#email', 'login-test@example.com')
  await page.fill('#password', 'conductor')
  await page.click('button[type="submit"]')

  await page.waitForURL('**/app/projects**', { timeout: 15000 })
  await expect(page).toHaveURL(/\/app\/projects/)
})

test('wrong password shows error, does not redirect', async ({ page }) => {
  await page.goto('/login')
  await page.fill('#email', 'login-test@example.com')
  await page.fill('#password', 'wrongpassword')
  await page.click('button[type="submit"]')

  await expect(page.getByText(/invalid email or password/i)).toBeVisible()

  await expect(page).toHaveURL(/\/login/)
})
