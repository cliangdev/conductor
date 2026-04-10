import { test, expect } from '@playwright/test'
import { setActiveProjectInStorage } from './helpers'

const BACKEND = 'http://localhost:8080'

async function getAuthToken(page: import('@playwright/test').Page): Promise<string> {
  return page.evaluate(() => localStorage.getItem('access_token') ?? '')
}

async function createProjectViaApi(token: string, request: import('@playwright/test').APIRequestContext) {
  const res = await request.post(`${BACKEND}/api/v1/projects`, {
    data: { name: `E2E Members ${Date.now()}`, description: 'Playwright members test' },
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
  expect(res.ok()).toBeTruthy()
  return res.json() as Promise<{ id: string; name: string }>
}

test('members page renders heading', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  expect(token).toBeTruthy()

  const project = await createProjectViaApi(token, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/members`)

  await expect(page.locator('h1')).toContainText('Members', { timeout: 15000 })
})

test('members page shows current user (playwright@example.com)', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/members`)
  await expect(page.locator('h1')).toContainText('Members', { timeout: 15000 })

  // playwright@example.com is the creator of the project and should appear in the list
  await expect(page.getByText('playwright@example.com').first()).toBeVisible({ timeout: 10000 })
})

test('members page shows Admin role for project creator', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/members`)
  await expect(page.locator('h1')).toContainText('Members', { timeout: 15000 })
  await expect(page.getByText('playwright@example.com').first()).toBeVisible({ timeout: 10000 })

  // The creator gets ADMIN role. MemberRow renders a Badge with "Admin" for the current user
  // (the select dropdown only appears for other members when isAdmin, not for the current user)
  await expect(page.getByText('Admin')).toBeVisible({ timeout: 10000 })
})

test('admin sees Invite Member button', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/members`)
  await expect(page.locator('h1')).toContainText('Members', { timeout: 15000 })

  // ADMIN users see the "Invite Member" button
  await expect(page.getByRole('button', { name: 'Invite Member' })).toBeVisible({ timeout: 10000 })
})

test('invite modal opens and shows email input', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/members`)
  await expect(page.locator('h1')).toContainText('Members', { timeout: 15000 })

  await page.getByRole('button', { name: 'Invite Member' }).click()

  // Modal should open with the email input
  await expect(page.locator('#invite-email')).toBeVisible({ timeout: 5000 })
  await expect(page.locator('#invite-role')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Send Invite' })).toBeVisible()
})

test('members page shows current user with "(you)" label', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/members`)
  await expect(page.locator('h1')).toContainText('Members', { timeout: 15000 })
  await expect(page.getByText('playwright@example.com').first()).toBeVisible({ timeout: 10000 })

  // MemberRow adds "(you)" label next to name for the current user
  await expect(page.getByText('(you)')).toBeVisible({ timeout: 10000 })
})
