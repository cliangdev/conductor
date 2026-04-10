import { test, expect } from '@playwright/test'
import { expectActiveProject, selectProject } from './helpers'

const BACKEND = 'http://localhost:8080'

async function getAuthToken(page: import('@playwright/test').Page): Promise<string> {
  return page.evaluate(() => localStorage.getItem('access_token') ?? '')
}

async function createProjectViaApi(
  token: string,
  name: string,
  request: import('@playwright/test').APIRequestContext,
) {
  const res = await request.post(`${BACKEND}/api/v1/projects`, {
    data: { name, description: 'Created by Playwright E2E test' },
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
  expect(res.ok()).toBeTruthy()
  return res.json() as Promise<{ id: string; name: string }>
}

test('create a new project and land on project page', async ({ page }) => {
  await page.goto('/app/projects/new')

  await expect(page.locator('#project-name')).toBeVisible()

  const projectName = `E2E Project ${Date.now()}`
  await page.fill('#project-name', projectName)
  await page.fill('#project-description', 'Created by Playwright E2E test')

  await page.click('button[type="submit"]')

  await page.waitForURL(/\/app\/projects\/[^/]+\/issues/, { timeout: 15000 })
  await expect(page).toHaveURL(/\/app\/projects\/[^/]+\/issues/)
  await expect(page.locator('h1, [data-testid="page-title"]')).not.toContainText('404')

  // The project selector dropdown should reflect the newly created project
  await expectActiveProject(page, projectName)
})

test('project selector shows current project after creation', async ({ page }) => {
  await page.goto('/app/projects/new')
  await expect(page.locator('#project-name')).toBeVisible()

  const projectName = `E2E Selector ${Date.now()}`
  await page.fill('#project-name', projectName)
  await page.click('button[type="submit"]')

  await page.waitForURL(/\/app\/projects\/[^/]+\/issues/, { timeout: 15000 })

  await expectActiveProject(page, projectName)
})

test('user can switch between projects via the dropdown', async ({ page, request }) => {
  // Hydrate auth
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  expect(token).toBeTruthy()

  const nameA = `E2E Switch A ${Date.now()}`
  const nameB = `E2E Switch B ${Date.now()}`
  const projectA = await createProjectViaApi(token, nameA, request)
  const projectB = await createProjectViaApi(token, nameB, request)

  // Navigate to project A
  await page.goto(`/app/projects/${projectA.id}/issues`)
  await page.evaluate((id) => localStorage.setItem('active_project_id', id), projectA.id)
  await page.reload()

  await expectActiveProject(page, nameA)

  // Switch to project B via the dropdown
  await selectProject(page, nameB)

  // After selecting project B the URL should update to project B's issues page
  await page.waitForURL(/\/app\/projects\/[^/]+\/issues/, { timeout: 15000 })
  await expectActiveProject(page, nameB)
})
