import { test, expect } from '@playwright/test'
import { setActiveProjectInStorage } from './helpers'

const BACKEND = 'http://localhost:8080'

async function getAuthToken(page: import('@playwright/test').Page): Promise<string> {
  return page.evaluate(() => localStorage.getItem('access_token') ?? '')
}

async function createProjectViaApi(token: string, request: import('@playwright/test').APIRequestContext) {
  const res = await request.post(`${BACKEND}/api/v1/projects`, {
    data: { name: `E2E Issue Detail ${Date.now()}`, description: 'Playwright test project' },
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
  expect(res.ok()).toBeTruthy()
  return res.json() as Promise<{ id: string; name: string }>
}

async function createIssueViaApi(
  token: string,
  projectId: string,
  request: import('@playwright/test').APIRequestContext
) {
  const res = await request.post(`${BACKEND}/api/v1/projects/${projectId}/issues`, {
    data: { type: 'PRD', title: `E2E Test Issue ${Date.now()}` },
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
  })
  expect(res.ok()).toBeTruthy()
  return res.json() as Promise<{ id: string; title: string; status: string }>
}

test('issue detail page renders issue title', async ({ page, request }) => {
  // Navigate to projects to hydrate auth context (token lands in localStorage)
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  expect(token).toBeTruthy()

  const project = await createProjectViaApi(token, request)
  const issue = await createIssueViaApi(token, project.id, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/issues/${issue.id}`)

  // Wait for the issue title to appear in the h1
  await expect(page.locator('h1')).toContainText(issue.title, { timeout: 15000 })
})

test('issue detail page does not show 404', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)
  const issue = await createIssueViaApi(token, project.id, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/issues/${issue.id}`)

  // Page should load without showing 404 or error
  await expect(page.locator('h1')).not.toContainText('404', { timeout: 15000 })
  await expect(page.locator('body')).not.toContainText('404')
})

test('empty document state shows appropriate message in sidebar', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)
  const issue = await createIssueViaApi(token, project.id, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/issues/${issue.id}`)

  // Wait for the page to finish loading (h1 with issue title visible)
  await expect(page.locator('h1')).toContainText(issue.title, { timeout: 15000 })

  // The sidebar shows "No documents attached yet" when there are no documents
  await expect(page.getByText('No documents attached yet').first()).toBeVisible({ timeout: 10000 })
})

test('issue detail page shows Documents section in sidebar', async ({ page, request }) => {
  await page.goto('/app/projects')
  await page.waitForURL(/\/app\/projects/, { timeout: 15000 })

  const token = await getAuthToken(page)
  const project = await createProjectViaApi(token, request)
  const issue = await createIssueViaApi(token, project.id, request)

  await setActiveProjectInStorage(page, project.id)
  await page.goto(`/app/projects/${project.id}/issues/${issue.id}`)

  await expect(page.locator('h1')).toContainText(issue.title, { timeout: 15000 })

  // Sidebar always renders the "Documents" label
  await expect(page.getByText('Documents', { exact: true })).toBeVisible({ timeout: 10000 })
})
